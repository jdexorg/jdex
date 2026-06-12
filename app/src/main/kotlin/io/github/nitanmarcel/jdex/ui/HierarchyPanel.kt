package io.github.nitanmarcel.jdex.ui

import io.github.andrewauclair.moderndocking.Dockable
import io.github.andrewauclair.moderndocking.app.Docking
import io.github.nitanmarcel.jdex.project.HClass
import io.github.nitanmarcel.jdex.project.HField
import io.github.nitanmarcel.jdex.project.HMembers
import io.github.nitanmarcel.jdex.project.HMethod
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class HierarchyPanel(
    private val onClass: (rawName: String) -> Unit,
    private val onMethod: (rawName: String, shortId: String) -> Unit,
    private val onField: (rawName: String, name: String) -> Unit,
    private val onDecompile: (fullName: String) -> Unit,
    private val loadMembers: (fullName: String) -> HMembers,
) : JPanel(BorderLayout()), Dockable {

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = JTree(model).apply { isRootVisible = false; showsRootHandles = true }

    init {
        Docking.registerDockable(this)
        add(JScrollPane(tree), BorderLayout.CENTER)

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(e: TreeExpansionEvent) {
                when (val node = e.path.lastPathComponent) {
                    is ClassTreeNode -> loadClass(node)
                    is MethodTreeNode -> loadMethod(node)
                }
            }

            override fun treeWillCollapse(e: TreeExpansionEvent) = Unit
        })
        tree.addTreeSelectionListener {
            when (val obj = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is HClass -> onClass(obj.rawName)
                is HMethod -> onMethod(obj.declaringRawName, obj.shortId)
                is HField -> onField(obj.declaringRawName, obj.name)
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                ((tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? HClass)
                    ?.let { onDecompile(it.fullName) }
            }
        })
    }

    fun clear() {
        root.removeAllChildren()
        model.reload()
    }

    fun show(classes: List<HClass>) {
        root.removeAllChildren()
        val packages = HashMap<String, DefaultMutableTreeNode>()

        fun packageNode(pkg: String): DefaultMutableTreeNode {
            if (pkg.isEmpty()) return root
            packages[pkg]?.let { return it }
            val parent = packageNode(pkg.substringBeforeLast('.', ""))
            val node = DefaultMutableTreeNode(PackageLabel(pkg.substringAfterLast('.')))
            parent.add(node)
            packages[pkg] = node
            return node
        }

        classes.forEach { packageNode(it.pkg).add(ClassTreeNode(it)) }
        sortTree(root)
        model.reload()
    }

    private fun loadClass(node: ClassTreeNode) {
        if (node.loaded) return
        node.loaded = true
        node.removeAllChildren()
        val members = loadMembers((node.userObject as HClass).fullName)
        members.fields.forEach { node.add(DefaultMutableTreeNode(it)) }
        members.methods.forEach { node.add(MethodTreeNode(it, members.innersByMethod[it.shortId] ?: emptyList())) }
        members.looseInners.forEach { node.add(ClassTreeNode(it)) }
        sortChildren(node)
        model.nodeStructureChanged(node)
    }

    private fun loadMethod(node: MethodTreeNode) {
        if (node.loaded) return
        node.loaded = true
        node.removeAllChildren()
        node.inners.forEach { node.add(ClassTreeNode(it)) }
        sortChildren(node)
        model.nodeStructureChanged(node)
    }

    private fun sortTree(node: DefaultMutableTreeNode) {
        sortChildren(node)
        (0 until node.childCount).map { node.getChildAt(it) as DefaultMutableTreeNode }
            .forEach { if (it.userObject is PackageLabel) sortTree(it) }
    }

    private fun sortChildren(node: DefaultMutableTreeNode) {
        val children = (0 until node.childCount).map { node.getChildAt(it) as DefaultMutableTreeNode }
        val sorted = children.sortedWith(compareBy({ rank(it) }, { it.userObject.toString().lowercase() }))
        node.removeAllChildren()
        sorted.forEach { node.add(it) }
    }

    private fun rank(node: DefaultMutableTreeNode) = when (node.userObject) {
        is PackageLabel -> 0
        is HField -> 1
        is HMethod -> 2
        is HClass -> 3
        else -> 4
    }

    private class PackageLabel(val name: String) {
        override fun toString() = name
    }

    private inner class ClassTreeNode(hClass: HClass) : DefaultMutableTreeNode(hClass) {
        var loaded = false

        init {
            add(DefaultMutableTreeNode("…"))
        }
    }

    private inner class MethodTreeNode(hMethod: HMethod, val inners: List<HClass>) : DefaultMutableTreeNode(hMethod) {
        var loaded = false

        init {
            if (inners.isNotEmpty()) add(DefaultMutableTreeNode("…"))
        }
    }

    override fun isWrappableInScrollpane() = false

    override fun getPersistentID() = "hierarchy"

    override fun getTabText() = "Hierarchy"
}
