package com.maichimod.prototype.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maichimod.prototype.MainActivity
import com.maichimod.prototype.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeWidgetHostTest {
    @Volatile private var received: List<WidgetField> = emptyList()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val spec = WidgetSpec("test", ComponentName("com.maichimod.prototype.test", TestInformationWidget::class.java.name), 116005, 280, 180, false)
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).readBytes()
    }

    @Test fun oldResultWithIdCannotCancelNewAttempt() = staleResult(false)
    @Test fun oldCancellationWithoutDataCannotCancelNewAttempt() = staleResult(true)
    private fun staleResult(withoutData: Boolean) {
        shell("appwidget revokebind --package ${context.packageName} --user 0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                val store = HomeInfoStore(activity, "home-widget-test")
                var code = -1
                val host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, request -> code = request }, {}, {})
                try {
                    host.connect(); val oldId = store.pendingId(spec.key); val oldCode = code
                    host.onActivityResult(oldCode, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, oldId))
                    host.connect(); val current = store.pendingId(spec.key)
                    assertTrue(current >= 0 && current != oldId)
                    host.onActivityResult(oldCode, if (withoutData) 0 else -1,
                        if (withoutData) null else Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, oldId))
                    assertEquals("Old result must leave the new pending transaction intact", current, store.pendingId(spec.key))
                    assertTrue(AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(current))
                } finally { host.disconnect(); host.close() }
            } }
        } finally { shell("appwidget revokebind --package ${context.packageName} --user 0") }
    }

    @Test fun pendingAuthorizationSurvivesRecreationAndCanComplete() = restoredPending(true)
    @Test fun pendingAuthorizationSurvivesRecreationAndCanCancel() = restoredPending(false)
    private fun restoredPending(complete: Boolean) {
        shell("appwidget revokebind --package ${context.packageName} --user 0")
        lateinit var restored: HomeWidgetHost
        lateinit var store: HomeInfoStore
        var pending = -1
        var code = -1
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                    store = HomeInfoStore(activity, "home-widget-test")
                    val first = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, request -> code = request }, {}, {})
                    first.connect(); pending = store.pendingId(spec.key); first.close()
                    restored = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> fail("Must await the original result") }, {}, {}, restoredPendingId = pending)
                    assertEquals("An outstanding system consent transaction must survive recreation", pending, store.pendingId(spec.key))
                    assertTrue(AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(pending))
                }
                if (complete) shell("appwidget grantbind --package ${context.packageName} --user 0")
                scenario.onActivity { activity ->
                    try {
                        if (complete) assertTrue(AppWidgetManager.getInstance(activity).bindAppWidgetIdIfAllowed(pending, spec.provider))
                        restored.onActivityResult(code, if (complete) -1 else 0, if (complete) Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending) else null)
                        assertEquals(-1, store.pendingId(spec.key))
                        assertEquals(if (complete) pending else -1, store.boundId(spec.key))
                        assertEquals(complete, AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(pending))
                    } finally { restored.disconnect(); restored.close() }
                }
            }
        } finally {
            AppWidgetHost(context, spec.hostId).deleteHost()
            shell("appwidget revokebind --package ${context.packageName} --user 0")
        }
    }

    @Test fun canceledAndAbandonedBindingsDeleteOnlyTheirOwnIds() {
        shell("appwidget revokebind --package ${context.packageName} --user 0")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = HomeInfoStore(activity, "home-widget-test")
                context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                var request: Intent? = null
                var requestCode = -1
                val host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { intent, code -> request = intent; requestCode = code }, {}, {})
                host.connect()
                assertNotNull(request)
                val canceled = store.pendingId(spec.key)
                assertTrue(canceled >= 0)
                host.onActivityResult(requestCode, 0, null)
                assertEquals(-1, store.pendingId(spec.key))
                assertFalse(AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(canceled))
                host.connect()
                val abandoned = store.pendingId(spec.key)
                assertTrue(abandoned >= 0)
                host.close()
                val restored = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> }, {}, {})
                assertFalse(AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(abandoned))
                assertEquals(-1, store.pendingId(spec.key))
                restored.onActivityResult(requestCode, -1, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, abandoned))
                assertEquals(-1, store.boundId(spec.key))
                restored.disconnect(); restored.close(); context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
            }
        }
    }

    @Test fun hiddenHostReceivesUpdatesStopsAndReusesBoundIdAcrossRecreation() {
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        lateinit var host: HomeWidgetHost
        lateinit var store: HomeInfoStore
        var count: Int? = null
        var boundId = -1
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    store = HomeInfoStore(activity, "home-widget-test"); context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                    host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> fail("Grant should permit binding") }, {}, { fields ->
                        received = fields
                        count = fields.singleOrNull { it.name == "tv_pkg_num" }?.text?.toIntOrNull()
                    })
                    host.start(); host.connect()
                    boundId = store.boundId(spec.key)
                    assertTrue(boundId >= 0)
                }
                try { waitFor { count == 2 } } catch (failure: AssertionError) {
                    var tree = ""
                    scenario.onActivity {
                        val frame = host.javaClass.getDeclaredField("frame").apply { isAccessible = true }.get(host) as android.view.View
                        fun dump(view: android.view.View) {
                            tree += "${view.javaClass.simpleName} #${view.id} ${runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()} ${(view as? android.widget.TextView)?.text}; "
                            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) dump(view.getChildAt(index))
                        }
                        dump(frame)
                    }
                    throw AssertionError("${failure.message}; tree: $tree", failure)
                }
                context.sendBroadcast(Intent("com.maichimod.prototype.test.CHANGE_WIDGET").setComponent(spec.provider).putExtra("number", "3"))
                waitFor { count == 3 }
                scenario.onActivity { activity ->
                    host.close(); count = null
                    host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> fail("Must reuse authorization") }, {}, { fields ->
                        received = fields
                        count = fields.singleOrNull { it.name == "tv_pkg_num" }?.text?.toIntOrNull()
                    })
                    host.start()
                    assertEquals(boundId, store.boundId(spec.key))
                }
                waitFor { count == 3 }
                scenario.onActivity { host.stop(); count = null }
                context.sendBroadcast(Intent("com.maichimod.prototype.test.CHANGE_WIDGET").setComponent(spec.provider).putExtra("number", "4"))
                Thread.sleep(600)
                assertNull(count)
                scenario.onActivity { host.start() }
                waitFor { count == 4 }
                scenario.onActivity {
                    host.disconnect(); host.close(); context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                    assertFalse(AppWidgetHost(it, spec.hostId).appWidgetIds.contains(boundId))
                }
            }
        } finally { shell("appwidget revokebind --package ${context.packageName} --user 0") }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for hosted widget data: $received", predicate())
    }
}
