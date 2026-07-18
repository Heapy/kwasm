@file:OptIn(ExperimentalObjCName::class)

package io.heapy.kwasm.footprint

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

@ObjCName("KwasmFootprintBaselineProbe", exact = true)
public class FootprintProbe {
    public fun run(): Int = runBlocking {
        yield()
        42
    }
}
