package dev.juniedeveloper.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.util.registry.RegistryValueListener
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.juniedeveloper.GitHubReleases
import dev.juniedeveloper.JunieDeveloperService
import dev.juniedeveloper.air.AirJunie
import dev.juniedeveloper.core.EntryKind
import dev.juniedeveloper.core.JunieEntry
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class JunieToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JunieLibraryPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** One table row: a library entry, or the built-in "default Junie" row when [entry] is null. */
private class Row(val entry: JunieEntry?)

class JunieLibraryPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val service = JunieDeveloperService.getInstance()
    private val model = LibraryTableModel()
    private val table = JBTable(model)
    private val status = JBLabel()
    private val refresh: () -> Unit = { reload() }

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).apply { maxWidth = JBUI.scale(60); preferredWidth = JBUI.scale(60) }
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(260)
        table.columnModel.getColumn(4).preferredWidth = JBUI.scale(420)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                useSelected(); return true
            }
        }.installOn(table)

        status.border = JBUI.Borders.empty(4, 8)
        val center = javax.swing.JPanel(BorderLayout())
        center.add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
        center.add(status, BorderLayout.SOUTH)
        setContent(center)

        val toolbar = ActionManager.getInstance().createActionToolbar("JunieDeveloper", actions(), true)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)

        service.addChangeListener(refresh)
        // the Registry can also be edited by hand; keep the "Active" column honest
        Registry.get(AirJunie.registryKey).addListener(object : RegistryValueListener {
            override fun afterValueChanged(value: RegistryValue) = refresh()
        }, this)
        reload()
    }

    override fun dispose() = service.removeChangeListener(refresh)

    private fun selected(): Row? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows[it] }

    private fun reload() {
        val keep = selected()?.entry?.id
        val hadSelection = table.selectedRow >= 0
        val active = service.activeEntry()
        val command = AirJunie.currentCommand()
        model.activeId = active?.id
        model.defaultActive = command.isEmpty()
        model.rows = listOf(Row(null)) + service.library.entries().sortedByDescending { it.addedAt }.map { Row(it) }
        model.fireTableDataChanged()
        if (hadSelection) {
            val index = model.rows.indexOfFirst { it.entry?.id == keep }.coerceAtLeast(0)
            table.selectionModel.setSelectionInterval(index, index)
        }
        status.text = when {
            !AirJunie.isAirAvailable() -> "The Air plugin is not installed or is disabled in this IDE."
            AirJunie.blocker() != null -> AirJunie.blocker()
            command.isEmpty() -> "Air uses the default Junie from the ACP registry."
            active != null -> "Air uses: ${active.name}"
            else -> "Air uses a command that is not in this library: $command"
        }
    }

    private fun useSelected() {
        val row = selected() ?: return
        service.activate(row.entry, project)
    }

    private fun actions() = DefaultActionGroup().apply {
        add(action("Use Selected Junie", "Point Air at the selected Junie and restart its agents",
            AllIcons.Actions.Execute, enabled = { selected() != null }) { useSelected() })
        add(action("Reset Agent Pool", "Stop the Junie agents Air keeps running, so the next message starts a fresh one",
            AllIcons.Actions.Restart) { service.resetPool(project) })
        add(Separator.getInstance())
        add(action("Download Junie Release…", "Search GitHub releases of ${GitHubReleases.REPOSITORY} and download one",
            AllIcons.Actions.Download) { ReleaseBrowserDialog(project).show() })
        add(action("Add Local JAR…", "Add a locally built Junie JAR to the list", AllIcons.General.Add) { addJar() })
        add(action("Remove", "Remove the selected entry; a downloaded release is deleted from disk",
            AllIcons.General.Remove, enabled = { selected()?.entry != null }) { removeSelected() })
        add(Separator.getInstance())
        add(action("GitHub Token…", "Optional token that lifts GitHub's limit of 60 requests an hour",
            AllIcons.Nodes.SecurityRole) { editToken() })
        add(action("Refresh", "Reload the list", AllIcons.Actions.Refresh) { reload() })
    }

    private fun addJar() {
        val descriptor = FileChooserDescriptor(true, false, true, true, false, false)
            .withTitle("Select Junie JAR")
            .withExtensionFilter("jar")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        try {
            val entry = service.addJar(Path.of(file.path))
            reload()
            val index = model.rows.indexOfFirst { it.entry?.id == entry.id }
            if (index >= 0) table.selectionModel.setSelectionInterval(index, index)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Add Local JAR")
        }
    }

    private fun removeSelected() {
        val entry = selected()?.entry ?: return
        val detail = if (entry.kind == EntryKind.RELEASE) "The downloaded files in\n${entry.path}\nwill be deleted."
        else "The JAR file itself is not deleted."
        if (Messages.showYesNoDialog(project, "Remove ${entry.name}?\n$detail", "Remove Junie", null) != Messages.YES) return
        service.remove(entry, project)
    }

    private fun editToken() {
        val hasToken = GitHubReleases.token != null
        val value = Messages.showPasswordDialog(project,
            "GitHub token for release searches (stored in the IDE password safe)." +
                (if (hasToken) "\nA token is saved. Leave empty and press OK to remove it." else ""),
            "GitHub Token", null) ?: return
        GitHubReleases.token = value
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon,
                       enabled: () -> Boolean = { true }, run: () -> Unit): AnAction =
        object : AnAction(text, description, icon), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private class LibraryTableModel : AbstractTableModel() {
        var rows: List<Row> = emptyList()
        var activeId: String? = null
        var defaultActive = false
        private val columns = listOf("Active", "Junie", "Type", "Version", "Location")

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = rows[rowIndex].entry
            return when (columnIndex) {
                0 -> if (entry == null && defaultActive || entry != null && entry.id == activeId) "●" else ""
                1 -> entry?.name ?: "Default (ACP registry)"
                2 -> when (entry?.kind) {
                    null -> "Built-in"
                    EntryKind.RELEASE -> "Downloaded" + (entry.channel?.let { " · $it" } ?: "")
                    EntryKind.JAR -> "Local JAR"
                }
                3 -> entry?.tag.orEmpty()
                else -> entry?.path ?: "Whatever Air installs by itself"
            }
        }
    }
}
