package co.ke.kumea.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit test for the TokenStore read/write contract.
 *
 * TokenStore depends on Android Context for its DataStore delegate, which won't
 * resolve on the JVM. Rather than pull in Robolectric for Sprint 0, this test
 * exercises an equivalent DataStore-backed implementation built from the same
 * primitives — proving the read/write/clear pattern is sound and stable across
 * the kotlinx-coroutines + DataStore combination locked in this catalog.
 *
 * The real TokenStore is wired into the app via Hilt and gets covered by the
 * on-device verification in the demo script.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tokenStore: TestTokenStore
    private val testScope = TestScope(UnconfinedTestDispatcher() + Job())

    @Before
    fun setUp() {
        val file: File = tempFolder.newFile("test_auth.preferences_pb")
        // DataStore requires the file not to exist at construction time, so delete it.
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { file },
        )
        tokenStore = TestTokenStore(dataStore)
    }

    @After
    fun tearDown() {
        // TestScope cleans itself up; nothing to do here.
    }

    @Test
    fun `tokenFlow emits null when no token has been saved`() = runTest {
        assertNull(tokenStore.tokenFlow.first())
    }

    @Test
    fun `saveToken then read returns the saved value`() = runTest {
        tokenStore.saveToken("test-token")
        assertEquals("test-token", tokenStore.tokenFlow.first())
    }

    @Test
    fun `saveToken overwrites a previous token`() = runTest {
        tokenStore.saveToken("first")
        tokenStore.saveToken("second")
        assertEquals("second", tokenStore.tokenFlow.first())
    }

    @Test
    fun `clearToken removes the saved value`() = runTest {
        tokenStore.saveToken("test-token")
        tokenStore.clearToken()
        assertNull(tokenStore.tokenFlow.first())
    }

    /**
     * Mirror of the production TokenStore, swapping the Context-backed DataStore
     * for an injected one. Same key, same Flow shape — exercising the exact
     * read/write contract the production class uses.
     */
    private class TestTokenStore(private val store: DataStore<Preferences>) {
        private val tokenKey = stringPreferencesKey("access_token")

        val tokenFlow: Flow<String?> = store.data.map { it[tokenKey] }

        suspend fun saveToken(token: String) {
            store.edit { it[tokenKey] = token }
        }

        suspend fun clearToken() {
            store.edit { it.remove(tokenKey) }
        }
    }
}
