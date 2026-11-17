package com.nuvio.tv.core.notification

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val own = "com.nuvio.tv.launcher"
    private fun show(flags: Int = 0, clearable: Boolean = true, pkg: String = "com.example", category: String? = null) =
        LauncherNotificationListenerService.shouldShow(flags, clearable, pkg, own, category)

    @Test fun `an ordinary clearable notification shows`() = assertTrue(show())
    @Test fun `ongoing notifications are hidden`() = assertFalse(show(flags = Notification.FLAG_ONGOING_EVENT))
    @Test fun `foreground service notifications are hidden`() = assertFalse(show(flags = Notification.FLAG_FOREGROUND_SERVICE))
    @Test fun `group summaries are hidden`() = assertFalse(show(flags = Notification.FLAG_GROUP_SUMMARY))
    @Test fun `notifications that cannot be cleared are hidden`() = assertFalse(show(clearable = false))
    @Test fun `service and system categories are hidden`() {
        assertFalse(show(category = Notification.CATEGORY_SERVICE))
        assertFalse(show(category = Notification.CATEGORY_SYSTEM))
    }
    @Test fun `our own notifications are hidden`() = assertFalse(show(pkg = own))
}
