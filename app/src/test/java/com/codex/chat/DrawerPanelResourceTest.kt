package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DrawerPanelResourceTest {

    @Test
    fun testDrawerItemUsesOfficialTypography() {
        val file = File("src/main/res/layout/item_drawer_conversation.xml")
        assertTrue("item_drawer_conversation.xml debe existir", file.exists())
        val text = file.readText()
        assertTrue("tvConvTitle debe usar @font/soehne", text.contains("@font/soehne"))
        assertTrue("tvConvCwd debe usar @font/menlo", text.contains("@font/menlo"))
    }

    @Test
    fun testDrawerButtonAndItemDrawablesHaveProperRadii() {
        val btnFile = File("src/main/res/drawable/bg_drawer_button.xml")
        val itemFile = File("src/main/res/drawable/bg_drawer_item.xml")
        assertTrue("bg_drawer_button.xml debe existir", btnFile.exists())
        assertTrue("bg_drawer_item.xml debe existir", itemFile.exists())

        val btnText = btnFile.readText()
        val itemText = itemFile.readText()
        assertTrue("bg_drawer_button debe tener radio 14dp", btnText.contains("14dp"))
        assertTrue("bg_drawer_item debe tener radio 12dp", itemText.contains("12dp"))
    }

    @Test
    fun testNavigationDrawerInActivityMainUsesOfficialTypography() {
        val file = File("src/main/res/layout/activity_main.xml")
        val text = file.readText()
        assertTrue("navigationDrawer debe usar @font/soehne", text.contains("android:id=\"@+id/navigationDrawer\""))
        assertTrue("tvDrawerPort debe usar @font/menlo", text.contains("@+id/tvDrawerPort") && text.contains("@font/menlo"))
    }

    @Test
    fun testBottomSheetsUseOfficialTypography() {
        val normalFile = File("src/main/res/layout/bottom_sheet_chatgpt_normal.xml")
        val actionsFile = File("src/main/res/layout/bottom_sheet_actions.xml")
        assertTrue("bottom_sheet_chatgpt_normal.xml debe existir", normalFile.exists())
        assertTrue("bottom_sheet_actions.xml debe existir", actionsFile.exists())

        assertTrue("Normal sheet debe usar @font/soehne", normalFile.readText().contains("@font/soehne"))
        assertTrue("Actions sheet debe usar @font/soehne", actionsFile.readText().contains("@font/soehne"))
        assertTrue("Actions sheet active cwd debe usar @font/menlo", actionsFile.readText().contains("@font/menlo"))
    }
}
