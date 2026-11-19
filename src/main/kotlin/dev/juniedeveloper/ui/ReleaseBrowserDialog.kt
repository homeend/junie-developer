package dev.juniedeveloper.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.juniedeveloper.GitHubReleases
import dev.juniedeveloper.JunieDeveloperService
import dev.juniedeveloper.core.JunieRelease
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Search and download dialog for Junie releases.
 *
 * GitHub has no server-side search over releases, so pages are loaded newest-first
 * and filtered here; a query that is an exact tag is also looked up directly, which
 * finds old builds without paging to them.
 */
class ReleaseBrowserDialog(private val project: Project) : DialogWrapper(project, true) {
    private val service = JunieDeveloperService.getInstance()
    private val loaded = LinkedHashMap<String, JunieRelease>()
    private var nextPage = 1
    private var exhausted = false
    private var loading = false

    private val search = SearchTextField(false)
    private val channel = ComboBox(arrayOf(ALL_CHANNELS))
    private val listModel = CollectionListModel<JunieRelease>()
    private val list = JBList(listModel)
    private val status = JBLabel(" ")
    private val more = JButton("Load Older Releases")

    init {
        title = "Download Junie Release (${GitHubReleases.REPOSITORY})"
        setOKButtonText("Download")
        channel.isEditable = false
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.emptyText.text = "No releases to show"
        list.addListSelectionListener { isOKActionEnabled = downloadable(list.selectedValue) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (isOKActionEnabled) doOKAction(); return true
            }
        }.installOn(list)
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        search.textEditor.addActionListener { lookUpExactTag() }
        channel.addActionListener { applyFilter(); loadUntilEnoughVisible() }
        more.addActionListener { loadPages(PAGES_PER_CLICK) }
        isOKActionEnabled = false
        init()
        loadPages(1)
    }

    override fun createCenterPanel(): JComponent {
        val top = JPanel(BorderLayout(JBUI.scale(8), 0))
        top.add(search, BorderLayout.CENTER)
        top.add(channel, BorderLayout.EAST)
        val bottom = JPanel(BorderLayout(JBUI.scale(8), 0))
        bottom.add(status, BorderLayout.CENTER)
        bottom.add(more, BorderLayout.EAST)
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
            preferredSize = JBUI.size(620, 460)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun doOKAction() {
        val release = list.selectedValue ?: return
        if (!downloadable(release)) return
        service.download(release, project)
        super.doOKAction()
    }

    private fun downloadable(release: JunieRelease?) =
        release != null && release.assetFor(service.platform) != null && !service.library.isInstalled(release)

    private fun visible(): List<JunieRelease> {
        val wanted = channel.selectedItem as String
        return loaded.values.filter {
            it.matches(search.text) && (wanted == ALL_CHANNELS || it.channel == wanted)
        }
    }

    /** The channel choices are whatever the loaded releases actually contain. */
    private fun syncChannels() {
        val wanted = channel.selectedItem
        val known = (0 until channel.itemCount).map { channel.getItemAt(it) }
        loaded.values.mapNotNull { it.channel }.distinct().sorted().filterNot { it in known }.forEach { channel.addItem(it) }
        channel.selectedItem = wanted
    }

    private fun applyFilter() {
        val keep = list.selectedValue?.tag
        val shown = visible()
        listModel.replaceAll(shown)
        shown.indexOfFirst { it.tag == keep }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        if (!loading) {
            status.text = "${shown.size} of ${loaded.size} loaded releases shown" +
                (if (exhausted) " · no older releases" else "") +
                (if (shown.isEmpty() && search.text.isNotBlank()) " · press Enter to look up that exact tag" else "")
        }
        more.isEnabled = !loading && !exhausted
    }

    /** A rare channel (EAP among hourly nightlies) may need several pages before anything shows. */
    private fun loadUntilEnoughVisible() {
        if (!loading && !exhausted && visible().size < MIN_VISIBLE) loadPages(PAGES_PER_CLICK)
    }

    private fun loadPages(count: Int) {
        if (loading || exhausted) return
        background("Loading releases…") {
            var pagesLeft = count
            val fetched = ArrayList<JunieRelease>()
            var end = false
            var page = nextPage
            while (pagesLeft-- > 0 && !end) {
                val batch = GitHubReleases.page(page++)
                fetched += batch
                end = batch.size < GitHubReleases.PAGE_SIZE
            }
            val reachedPage = page
            val reachedEnd = end
            return@background {
                fetched.forEach { loaded.putIfAbsent(it.tag, it) }
                nextPage = reachedPage
                exhausted = reachedEnd
            }
        }
    }

    private fun lookUpExactTag() {
        val tag = search.text.trim()
        if (tag.isEmpty() || loaded.containsKey(tag) || loading) return
        background("Looking up $tag…") {
            val release = GitHubReleases.byTag(tag)
            return@background { release?.let { loaded[it.tag] = it } }
        }
    }

    /** Runs [work] off the EDT; the block it returns is applied on the EDT. */
    private fun background(message: String, work: () -> (() -> Unit)) {
        loading = true
        status.toolTipText = null
        status.text = message
        more.isEnabled = false
        val modality = ModalityState.stateForComponent(rootPane)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({
                loading = false
                result.onSuccess { it(); syncChannels() }
                applyFilter()
                result.onFailure {
                    // long, so it goes in the tooltip too; the label alone would cut it off
                    status.text = it.message ?: it.toString()
                    status.toolTipText = it.message
                }
            }, modality)
        }
    }

    private inner class Renderer : ColoredListCellRenderer<JunieRelease>() {
        override fun customizeCellRenderer(list: JList<out JunieRelease>, value: JunieRelease, index: Int,
                                           selected: Boolean, hasFocus: Boolean) {
            append(value.title)
            val asset = value.assetFor(service.platform)
            val details = buildList {
                add(value.publishedAt.take(16).replace('T', ' '))
                add(when {
                    service.library.isInstalled(value) -> "downloaded"
                    asset == null -> "no ${service.platform.os}-${service.platform.arch} build"
                    else -> StringUtil.formatFileSize(asset.size)
                })
            }
            append("   " + details.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        const val ALL_CHANNELS = "All channels"
        const val PAGES_PER_CLICK = 3
        const val MIN_VISIBLE = 15
    }
}
