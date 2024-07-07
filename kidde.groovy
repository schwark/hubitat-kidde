/**
 *  Kidde HomeSafe Support for Hubitat
 *  Schwark Satyavolu
 *
 */

import hubitat.helper.InterfaceUtils
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.InvalidKeyException
import java.util.Random
import java.util.Date
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.security.MessageDigest
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovyx.net.http.HttpResponseException
import java.time.ZoneId
import java.net.URLEncoder
import java.time.Instant

def BASE_URI() { 'https://api.homesafe.kidde.com/api/v4' }


def version() {"0.0.1"}
def appVersion() { return version() }
def appName() { return "Kidde HomeSafe" }

definition(
    name: "Kidde HomeSafe",
    namespace: "schwark",
    author: "Schwark Satyavolu",
    description: "This adds support for Kidde HomeSafe Devices",
    category: "Convenience",
    iconUrl: "https://play-lh.googleusercontent.com/F7-vRjYhey9VlEa5pI5VL5Dny0xEVZEuffSvoN1q9bbDW_8uTEJVMM6d66YfJsonbRe9=s96",
    iconX2Url: "https://play-lh.googleusercontent.com/F7-vRjYhey9VlEa5pI5VL5Dny0xEVZEuffSvoN1q9bbDW_8uTEJVMM6d66YfJsonbRe9=s96",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/schwark/hubitat-kidde/main/kidde.groovy"
)

preferences {
    page(name: "mainPage")
}


def kidde_api(path, data=null, headers=null, method=null, closure) {
    def access_token = state.access_token
    def contentType = 'application/json'
    if(!headers) headers = [:]
    if(access_token && !headers) headers['homeboy-auth'] = access_token
    def uri = "${BASE_URI()}/${path}"
    method = method ?: (data ? 'POST' : 'GET')
    if(data && 'GET' == method) {
        params = data.collect {k,v -> "${URLEncoder.encode(k.toString())}=${URLEncoder.encode(v.toString())}"}.join('&')
        uri = "${uri}?${params}"
        data = null
    }
    debug("uri: ${uri}, data: ${data}, headers: ${headers}, method: ${method}")
    "http${method.toLowerCase().capitalize()}"([uri: uri, headers: headers, body: JsonOutput.toJson(data), contentType: contentType], closure)
}

def ensure_access_token() {
    if(!state.access_token) {
        kidde_api("auth/login", ["email": settings['email'], "password": settings['password'], "timezone": "America/Los_Angeles"], 
            [
                "homeboy-app-platform": "android",
                "homeboy-app-version": "4.0.12",
                "homeboy-app-platform-version": "12",
                "homeboy-app-id": "afc41e9816b1f0d7",
                "homeboy-app-brand": "google",
                "homeboy-app-device": "sdk_gphone64_x86_64",
                "cache-control": "max-age=0",
                "homeboy-app": "com.kidde.android.monitor1",
                "user-agent": "com.kidde.android.monitor1/4.0.12"
            ], null) {
            json = it.data
            if(json['access_token']) {
                debug("setting access token to "+json['access_token'])
                state.access_token = json['access_token']
            }
        }
    }
    return state.access_token
}

def update_devices() {
    state.devices = state.devices ?: [:]
    state.locations = state.locations ?: [:]
    ensure_access_token()
    kidde_api("location", null, null, null) {
        def json = it.data
        for(location in json) {
            state.locations[location.id] = location
            kidde_api("location/${location.id}/device", null, null, null) {
                for(device in it.data) {
                    state.devices[device.id] = device
                }
            }
        }
    }
    // clean up removed devices
    def children = getAllChildDevices()
    for(child in children) {
        def id = getDeviceId(child.id)
        if(!state.devices[id]) {
            deleteChildDevice(child.id)
        }
    }

    for(entry in state.devices) {
        def id = entry.key
        def meta = entry.value
        def name = meta.label
        device = createChildDevice(name, id)
        def caps = [
            "iaq_temperature": ['name': "temperature", 'subkey': 'value'],
            "humidity": ['name': "humidity", 'subkey': 'value'],
            "iaq": ['name': "airQualityIndex", 'subkey': 'value'],
            "co2": ['name': "carbonDioxide", 'subkey': 'value'],
            "smoke_alarm": ['name': "smoke", 'values': ['true': 'detected', 'false': 'clear']],
            "co_alarm": ['name': "carbonMonoxide", 'values': ['true': 'detected', 'false': 'clear']]
        ]
        for (cap in caps) {
            key = cap.key
            if(null != meta[key]) {
                def val = cap.value.subkey ? meta[key][cap.value.subkey] : meta[key]
                def state = (cap.value.values ? cap.value.values[String.valueOf(val)] : val)
                debug("sending value ${state} to attribute ${cap.value.name}")
                device.sendEvent(name: cap.value.name, value: state, isStateChange: true)
            }
        }
    }
}

def getFormat(type, myText=""){
    if(type == "section") return "<div style='color:#78bf35;font-weight: bold'>${myText}</div>"
    if(type == "hlight") return "<div style='color:#78bf35'>${myText}</div>"
    if(type == "header") return "<div style='color:#ffffff;background-color:#392F2E;text-align:center'>${myText}</div>"
    if(type == "redhead") return "<div style='color:#ffffff;background-color:red;text-align:center'>${myText}</div>"
    if(type == "line") return "\n<hr style='background-color:#78bf35; height: 2px; border: 0;'></hr>"
    if(type == "centerBold") return "<div style='font-weight:bold;text-align:center'>${myText}</div>"    
}

def mainPage(){
    dynamicPage(name:"mainPage",install:true, uninstall:true){
        section {
            input "debugMode", "bool", title: "Enable debugging", defaultValue: true
        }
        section(getFormat("header", "Login Information")) {
            input "email", "text", title: "Email", required: true
            input "password", "text", title: "Password", required: true
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
    def force = false
    if(!state.last_username || state.last_username != username || !state.last_password || state.last_password != password) {
        state.last_username = username
        state.last_password = password
        force = true
    }
    refresh(force)
    runEvery1Minute('refresh')
}

def initialize() {
    unschedule()
}

def uninstalled() {
    def children = getAllChildDevices()
    log.info("uninstalled: children = ${children}")
    children.each {
        deleteChildDevice(it.deviceNetworkId)
    }
}

def componentRefresh(cd) {
    refresh()
}

def refresh(force=false) {
    debug("refreshing Kidde HomeSafe...")
    ensure_access_token()
    update_devices()
}

def getDeviceId(id) {
    return id.split('-')[0]
}

private createChildDevice(label, id) {
    def deviceId = String.valueOf(id)
    def createdDevice = getChildDevice(deviceId)
    def name = "Kidde HomeSafe Device"

    if(!createdDevice) {
        try {
            def component = 'Generic Component Omni Sensor'
            // create the child device
            addChildDevice("hubitat", component, deviceId, [label : "${label}", isComponent: false, name: "${name}"])
            createdDevice = getChildDevice(deviceId)
            def created = createdDevice ? "created" : "failed creation"
            log.info("[Kidde HomeSafe] id: ${deviceId} label: ${label} ${created}")
        } catch (e) {
            logError("Failed to add child device with error: ${e}", "createChildDevice()")
        }
    } else {
        debug("Child device id: ${deviceId} already exists", "createChildDevice()")
        if(label && label != createdDevice.getLabel()) {
            createdDevice.sendEvent(name:'label', value: label, isStateChange: true)
        }
        if(name && name != createdDevice.getName()) {
            createdDevice.setName(name)
            createdDevice.sendEvent(name:'name', value: name, isStateChange: true)
        }
    }
    return createdDevice
}

private debug(logMessage, fromMethod="") {
    if (debugMode) {
        def fMethod = ""

        if (fromMethod) {
            fMethod = ".${fromMethod}"
        }

        log.debug("[Kidde HomeSafe] DEBUG: ${fMethod}: ${logMessage}")
    }
}

private logError(fromMethod, e) {
    log.error("[Kidde HomeSafe] ERROR: (${fromMethod}): ${e}")
}
