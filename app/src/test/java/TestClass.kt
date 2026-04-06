package com.example.remotesupportheadset

import org.junit.Test
import java.lang.reflect.Method

class TestClass {
    @Test
    fun printTypes() {
        val methods = Class.forName("com.jiangdg.ausbc.callback.IDeviceConnectCallBack").methods
        for (m in methods) {
            println(m.name)
            for (p in m.parameterTypes) {
                println("PARAM: " + p.name)
            }
        }
    }
}
