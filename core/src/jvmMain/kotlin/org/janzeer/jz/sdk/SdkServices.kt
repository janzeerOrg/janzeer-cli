package org.janzeer.jz.sdk

import org.janzeer.jz.api.ChainApi
import org.janzeer.jz.api.KeyOps
import org.janzeer.jz.api.SdkInfo
import org.janzeer.jz.api.Services
import org.janzeer.jz.config.Profile
import org.janzeer.sdk.SpecVersion
import java.util.Properties

/** The real services: everything is built on the published Kotlin SDK. */
class SdkServices : Services {
    private val buildProps: Properties by lazy {
        Properties().also { p -> SdkServices::class.java.getResourceAsStream("/jz-build.properties")?.use { p.load(it) } }
    }

    override val sdk: SdkInfo = object : SdkInfo {
        override val sdkNode = SpecVersion.NODE
        override val sdkApi = SpecVersion.API
        override val sdkProtocol = SpecVersion.PROTOCOL
        override val sdkVectors = SpecVersion.VECTORS
        override val sdkVersion get() = buildProps.getProperty("sdk", "?")
    }

    override val keys: KeyOps = SdkKeyOps()
    override val vectors: org.janzeer.jz.api.VectorsCheck = SdkVectors()

    override fun chain(profile: Profile, timeoutSec: Int): ChainApi = SdkChainApi(profile, timeoutSec)
}
