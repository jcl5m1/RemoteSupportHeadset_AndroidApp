package com.example.remotesupportheadset

import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import java.lang.reflect.Method

fun test() {
    val methods = IDeviceConnectCallBack::class.java.methods
    for (m in methods) {
        println(m.name)
        for (p in m.parameterTypes) {
            println(p.name)
        }
    }
}
