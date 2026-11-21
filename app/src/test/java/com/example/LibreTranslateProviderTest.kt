package com.example

import com.example.engine.LibreTranslateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibreTranslateProviderTest {
    @Test
    fun resolveEndpoint_normalizesUserInput() {
        assertNull(LibreTranslateProvider.resolveEndpoint("  "))
        assertEquals("https://libretranslate.com/translate", LibreTranslateProvider.resolveEndpoint("libretranslate.com"))
        assertEquals("http://192.168.1.5:5000/translate", LibreTranslateProvider.resolveEndpoint("http://192.168.1.5:5000/"))
        assertEquals("https://host/translate", LibreTranslateProvider.resolveEndpoint("https://host/translate"))
    }
}
