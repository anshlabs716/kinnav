package org.geonav.family

import android.content.Context
import org.geonav.family.crypto.CryptoEngine
import org.geonav.family.db.FamilyDatabase
import org.geonav.family.finding.FindingRepository
import org.geonav.family.network.RelayClient
import org.geonav.family.repository.FamilyRepository
import org.geonav.family.service.FamilyNotificationHelper

/**
 * The seam between this module and the host app.
 *
 * :family cannot reference GeoNav's Application, positioning stack or UI, so the app
 * implements this interface and passes it to [init]. Everything the sharing engine needs
 * from the outside world flows through here.
 */
interface FamilyHooks {

    /**
     * Called when sharing turns on (and whenever the share service restarts). The host
     * should make sure its location pipeline is running; calling it twice must be safe.
     */
    fun startLocationUpdates()

    /**
     * Called when sharing turns off or the share service stops. The host should stop
     * consuming battery for fixes nobody will receive; the app's own navigation-grade
     * positioning (only while navigating) is a separate concern and keeps running.
     */
    fun stopLocationUpdates() {}

    /**
     * Extra diagnostic rows contributed by the host app (positioning stack, sensors,
     * fusion state). Only read when the debug diagnostics screen is opened.
     */
    fun diagnosticRows(): List<Pair<String, String>> = emptyList()
}

/**
 * Service locator for the sharing subsystem.
 *
 * Mirrors how GeoNavApplication exposes its own repositories: the Application calls [init]
 * once in onCreate(), and the foreground service, Quick Settings tile and boot receiver
 * reach the repository through [repository] without needing a reference to the app class
 * (which this module must not depend on).
 */
object Family {

    @Volatile
    private var repositoryInstance: FamilyRepository? = null

    @Volatile
    private var findingInstance: FindingRepository? = null

    @Volatile
    var hooks: FamilyHooks? = null
        private set

    /** The sharing repository, or null before [init] runs. */
    val repository: FamilyRepository?
        get() = repositoryInstance

    /** The item-finding repository, or null before [init] runs. */
    val finding: FindingRepository?
        get() = findingInstance

    /**
     * Builds the sharing stack and starts its inbound pipeline. Idempotent: the first call
     * wins, later calls return the existing instance.
     */
    @Synchronized
    fun init(context: Context, hooks: FamilyHooks): FamilyRepository {
        repositoryInstance?.let { return it }
        this.hooks = hooks
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(FAMILY_PREFS, Context.MODE_PRIVATE)
        val crypto = CryptoEngine(prefs)
        val dao = FamilyDatabase.getInstance(appContext).familyDao()
        val notificationHelper = FamilyNotificationHelper(appContext)
        val relayClient = RelayClient(prefs, crypto)
        val repository = FamilyRepository(
            context = appContext,
            dao = dao,
            prefs = prefs,
            cryptoEngine = crypto,
            relayClient = relayClient,
            notificationHelper = notificationHelper
        )
        repository.start()
        repositoryInstance = repository
        findingInstance = FindingRepository(
            context = appContext,
            prefs = prefs,
            relayClient = relayClient,
            // Finding helper reports want the freshest fix even when the sharing master
            // switch is off; the fix cache is in-memory and gated from transmission.
            fixProvider = { repository.latestSubmittedFix },
            onLog = { message -> repository.appendLog(message) }
        )
        return repository
    }

    /** Test seam so unit tests can install a repository without an Application. */
    fun setRepositoryForTesting(repository: FamilyRepository?) {
        repositoryInstance = repository
    }

    const val FAMILY_PREFS = "kinnav_family"
}
