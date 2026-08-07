package com.unique.visionmate.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {

    @Test
    fun globalFlag_defaultsDisabled() {
        assertFalse(FeatureFlags.isGlobalEnabled(TestSharedPreferences()))
    }

    @Test
    fun globalFlag_readsExplicitEnabled() {
        val prefs = TestSharedPreferences()
        prefs.putBoolean(FeatureFlags.GLOBAL_KEY, true)

        assertTrue(FeatureFlags.isGlobalEnabled(prefs))
    }

    @Test
    fun perFeatureFlags_defaultEnabled() {
        val flags = FeatureFlags(TestSharedPreferences())

        Feature.values().forEach { feature ->
            assertTrue("${feature.name} should default enabled", flags.isEnabled(feature))
        }
    }

    @Test
    fun perFeatureFlags_disableOnlyRequestedFeature() {
        val prefs = TestSharedPreferences()
        prefs.putBoolean("offload.pothole.enabled", false)
        val flags = FeatureFlags(prefs)

        assertFalse(flags.isEnabled(Feature.POTHOLE))
        assertTrue(flags.isEnabled(Feature.FIRE_SMOKE))
    }
}
