package com.pockethound.app

import android.app.Application
import computer.iroh.IrohAndroid
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PocketHoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // O iroh e uma biblioteca nativa e precisa do contexto do Android para
        // achar o diretorio de dados antes do primeiro bind do endpoint. Sem
        // isto, o primeiro `P2pTransport.probe()` falha com um erro nativo que
        // nao diz o que faltou.
        IrohAndroid.installAndroidContext(this)
    }
}
