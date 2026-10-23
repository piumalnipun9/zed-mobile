package dev.zed.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalOptionLabelTest {
    private val terminalChoices = listOf(
        PermissionChoice("always_allow:terminal", "Always for terminal", PERMISSION_ALLOW_ALWAYS),
        PermissionChoice("always_deny:terminal", "Always for terminal", PERMISSION_REJECT_ALWAYS),
        PermissionChoice("allow", "Only this time", PERMISSION_ALLOW_ONCE),
        PermissionChoice("deny", "Only this time", PERMISSION_REJECT_ONCE),
    )

    @Test
    fun `paired options sharing a label name their action`() {
        assertEquals(
            listOf("Allow always for terminal", "Deny always for terminal", "Allow only this time", "Deny only this time"),
            terminalChoices.map { optionLabel(it, terminalChoices) },
        )
    }

    @Test
    fun `distinct labels are shown as the desktop sent them`() {
        val choices = listOf(
            PermissionChoice("allow", "Allow", PERMISSION_ALLOW_ONCE),
            PermissionChoice("deny", "Deny", PERMISSION_REJECT_ONCE),
        )
        assertEquals(listOf("Allow", "Deny"), choices.map { optionLabel(it, choices) })
    }
}
