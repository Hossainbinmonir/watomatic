package com.parishod.watomatic.testing

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.util.Collections

/**
 * Reusable JUnit 4 [ExternalResource] managing isolated per-test storage, runtime permission
 * setup where supported, singleton resets, and guaranteed teardown of test-owned notifications
 * and local mock HTTP servers.
 */
class WatomaticTestRule(
    private val prefixTag: String = "watotest",
    private val grantPostNotificationsOnApi33Plus: Boolean = true
) : ExternalResource() {

    lateinit var targetContext: Context
        private set

    lateinit var isolatedContext: IsolatedTestContext
        private set

    private val registeredCloseables = Collections.synchronizedList(mutableListOf<AutoCloseable>())

    override fun before() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        isolatedContext = IsolatedTestContext(
            base = targetContext,
            prefix = IsolatedTestContext.generatePrefix(prefixTag)
        )
        IsolatedTestContext.resetAllAppSingletons(isolatedContext)
        if (grantPostNotificationsOnApi33Plus) {
            grantPostNotificationsPermissionIfNeeded()
        }
    }

    override fun after() {
        val snapshot = synchronized(registeredCloseables) {
            val copy = registeredCloseables.toList()
            registeredCloseables.clear()
            copy
        }
        // Close in reverse registration order (e.g., fixtures/servers before DB/storage teardown).
        for (resource in snapshot.asReversed()) {
            runCatching { resource.close() }
        }
        if (::isolatedContext.isInitialized) {
            runCatching { isolatedContext.cleanupOwnedStorage() }
        }
    }

    /**
     * Registers an [AutoCloseable] (such as [SyntheticNotificationFixture] or [LocalAiMockServer])
     * to be deterministically closed during [after], even when assertions fail.
     */
    fun <T : AutoCloseable> register(resource: T): T {
        registeredCloseables.add(resource)
        return resource
    }

    fun createNotificationFixture(): SyntheticNotificationFixture {
        return register(SyntheticNotificationFixture(isolatedContext))
    }

    fun createMockAiServer(autoStart: Boolean = true): LocalAiMockServer {
        val server = LocalAiMockServer()
        if (autoStart) {
            server.start()
        }
        return register(server)
    }

    fun grantPostNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            uiAutomation.grantRuntimePermission(
                targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}
