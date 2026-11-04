package com.braveplayer.music.jam

import com.braveplayer.music.jam.network.JamApiService
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class JamApiServiceTest {

    private val apiService = JamApiService()

    @Test
    fun testLocalHostUrlConstruction() {
        val httpUrl = apiService.getBaseHttpUrl("127.0.0.1", 8080)
        val wsUrl = apiService.getBaseWsUrl("127.0.0.1", 8080)

        assertThat(httpUrl).isEqualTo("http://127.0.0.1:8080")
        assertThat(wsUrl).isEqualTo("ws://127.0.0.1:8080/ws/jam")
    }

    @Test
    fun testProductionDomainUrlConstruction() {
        val httpUrl = apiService.getBaseHttpUrl("melody-jam.onrender.com", 443)
        val wsUrl = apiService.getBaseWsUrl("melody-jam.onrender.com", 443)

        assertThat(httpUrl).isEqualTo("https://melody-jam.onrender.com")
        assertThat(wsUrl).isEqualTo("wss://melody-jam.onrender.com/ws/jam")
    }

    @Test
    fun testExplicitHttpsPrefixUrlConstruction() {
        val httpUrl = apiService.getBaseHttpUrl("https://jam.melody.app", 443)
        val wsUrl = apiService.getBaseWsUrl("https://jam.melody.app", 443)

        assertThat(httpUrl).isEqualTo("https://jam.melody.app")
        assertThat(wsUrl).isEqualTo("wss://jam.melody.app/ws/jam")
    }

    @Test
    fun testCustomPortUrlConstruction() {
        val httpUrl = apiService.getBaseHttpUrl("192.168.1.50", 9000)
        val wsUrl = apiService.getBaseWsUrl("192.168.1.50", 9000)

        assertThat(httpUrl).isEqualTo("http://192.168.1.50:9000")
        assertThat(wsUrl).isEqualTo("ws://192.168.1.50:9000/ws/jam")
    }
}
