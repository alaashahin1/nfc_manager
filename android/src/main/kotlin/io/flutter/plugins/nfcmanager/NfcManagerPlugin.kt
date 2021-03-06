package io.flutter.plugins.nfcmanager

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
import android.os.Build

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.IOException
import java.lang.Exception
import kotlin.experimental.and
import kotlin.experimental.or
import java.util.*

class NfcManagerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private lateinit var activity: Activity
    private lateinit var tags: MutableMap<String, Tag>
    private var adapter: NfcAdapter? = null
    private var connectedTech: TagTechnology? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "plugins.flutter.io/nfc_manager")
        channel.setMethodCallHandler(this)
        adapter = NfcAdapter.getDefaultAdapter(binding.applicationContext)
        tags = mutableMapOf()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        // no op
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // no op
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "Nfc#isAvailable" -> handleNfcIsAvailable(call, result)
            "Nfc#startSession" -> handleNfcStartSession(call, result)
            "Nfc#stopSession" -> handleNfcStopSession(call, result)
            "Nfc#disposeTag" -> handleNfcDisposeTag(call, result)
            "Ndef#read" -> handleNdefRead(call, result)
            "Ndef#write" -> handleNdefWrite(call, result)
            "Ndef#writeLock" -> handleNdefWriteLock(call, result)
            "NfcA#transceive" -> handleNfcATransceive(call, result)
            "NfcB#transceive" -> handleNfcBTransceive(call, result)
            "NfcF#transceive" -> handleNfcFTransceive(call, result)
            "NfcV#transceive" -> handleNfcVTransceive(call, result)
            "IsoDep#transceive" -> handleIsoDepTransceive(call, result)
            "MifareClassic#authenticateSectorWithKeyA" -> handleMifareClassicAuthenticateSectorWithKeyA(
                call,
                result
            )
            "MifareClassic#authenticateSectorWithKeyB" -> handleMifareClassicAuthenticateSectorWithKeyB(
                call,
                result
            )
            "MifareClassic#increment" -> handleMifareClassicIncrement(call, result)
            "MifareClassic#decrement" -> handleMifareClassicDecrement(call, result)
            "MifareClassic#readBlock" -> handleMifareClassicReadBlock(call, result)
            "MifareClassic#writeBlock" -> handleMifareClassicWriteBlock(call, result)
            "MifareClassic#restore" -> handleMifareClassicRestore(call, result)
            "MifareClassic#transfer" -> handleMifareClassicTransfer(call, result)
            "MifareClassic#transceive" -> handleMifareClassicTransceive(call, result)
            "MifareUltralight#readPages" -> handleMifareUltralightReadPages(call, result)
            "MifareUltralight#writePage" -> handleMifareUltralightWritePage(call, result)
            "MifareUltralight#transceive" -> handleMifareUltralightTransceive(call, result)
            "Nfc#setPassword" -> handleSetPassword(call, result)
            "Nfc#removePassword" -> handleRemovePassword(call, result)
            "NdefFormatable#format" -> handleNdefFormatableFormat(call, result)
            "NdefFormatable#formatReadOnly" -> handleNdefFormatableFormatReadOnly(call, result)
            else -> result.notImplemented()
        }
    }

    private fun handleNfcIsAvailable(call: MethodCall, result: Result) {
        result.success(adapter?.isEnabled == true)
    }

    private fun handleNfcStartSession(call: MethodCall, result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            result.error("unavailable", "Requires API level 19.", null)
        } else {
            val adapter = adapter ?: run {
                result.error("unavailable", "NFC is not available for device.", null)
                return
            }
            adapter.enableReaderMode(activity, {
                val handle = UUID.randomUUID().toString()
                tags[handle] = it
                activity.runOnUiThread {
                    channel.invokeMethod(
                        "onDiscovered",
                        getTagMap(it).toMutableMap().apply { put("handle", handle) })
                }
            }, getFlags(call.argument<List<String>>("pollingOptions")!!), null)
            result.success(null)
        }
    }

    private fun handleNfcStopSession(call: MethodCall, result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            result.error("unavailable", "Requires API level 19.", null)
        } else {
            val adapter = adapter ?: run {
                result.error("unavailable", "NFC is not available for device.", null)
                return
            }
            adapter.disableReaderMode(activity)
            result.success(null)
        }
    }

    private fun handleNfcDisposeTag(call: MethodCall, result: Result) {
        val tag = tags.remove(call.argument<String>("handle")!!) ?: run {
            result.success(null)
            return
        }

        val tech = connectedTech ?: run {
            result.success(null)
            return
        }

        if (tech.tag == tag && tech.isConnected)
            try {
                tech.close()
            } catch (e: IOException) { /* no op */
            }

        connectedTech = null

        result.success(null)
    }

    private fun handleNdefRead(call: MethodCall, result: Result) {
        tagHandler(call, result, { Ndef.get(it) }) {
            val message = it.ndefMessage
            result.success(if (message == null) null else getNdefMessageMap(message))
        }
    }

    private fun handleNdefWrite(call: MethodCall, result: Result) {
        tagHandler(call, result, { Ndef.get(it) }) {
            val message = getNdefMessage(call.argument<Map<String, Any?>>("message")!!)
            it.writeNdefMessage(message)
            result.success(null)
        }
    }

    private fun handleNdefWriteLock(call: MethodCall, result: Result) {
        tagHandler(call, result, { Ndef.get(it) }) {
            it.makeReadOnly()
            result.success(null)
        }
    }

    private fun handleNfcATransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { NfcA.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleNfcBTransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { NfcB.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleNfcFTransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { NfcF.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleNfcVTransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { NfcV.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleIsoDepTransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { IsoDep.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleMifareClassicAuthenticateSectorWithKeyA(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val sectorIndex = call.argument<Int>("sectorIndex")!!
            val key = call.argument<ByteArray>("key")!!
            result.success(it.authenticateSectorWithKeyA(sectorIndex, key))
        }
    }

    private fun handleMifareClassicAuthenticateSectorWithKeyB(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val sectorIndex = call.argument<Int>("sectorIndex")!!
            val key = call.argument<ByteArray>("key")!!
            result.success(it.authenticateSectorWithKeyB(sectorIndex, key))
        }
    }

    private fun handleMifareClassicIncrement(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val blockIndex = call.argument<Int>("blockIndex")!!
            val value = call.argument<Int>("value")!!
            it.increment(blockIndex, value)
            result.success(null)
        }
    }

    private fun handleMifareClassicDecrement(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val blockIndex = call.argument<Int>("blockIndex")!!
            val value = call.argument<Int>("value")!!
            it.decrement(blockIndex, value)
            result.success(null)
        }
    }

    private fun handleMifareClassicReadBlock(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val blockIndex = call.argument<Int>("blockIndex")!!
            result.success(it.readBlock(blockIndex))
        }
    }

    private fun handleMifareClassicWriteBlock(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val blockIndex = call.argument<Int>("blockIndex")!!
            val data = call.argument<ByteArray>("data")!!
            it.writeBlock(blockIndex, data)
            result.success(null)
        }
    }

    private fun handleMifareClassicRestore(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val blockIndex = call.argument<Int>("blockIndex")!!
            it.restore(blockIndex)
            result.success(null)
        }
    }

    private fun handleMifareClassicTransfer(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val blockIndex = call.argument<Int>("blockIndex")!!
            it.transfer(blockIndex)
            result.success(null)
        }
    }

    private fun handleMifareClassicTransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareClassic.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleMifareUltralightReadPages(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareUltralight.get(it) }) {
            val pageOffset = call.argument<Int>("pageOffset")!!
            result.success(it.readPages(pageOffset))
        }
    }

    private fun handleMifareUltralightWritePage(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareUltralight.get(it) }) {
            val pageOffset = call.argument<Int>("pageOffset")!!
            val data = call.argument<ByteArray>("data")!!
            it.writePage(pageOffset, data)
            result.success(null)
        }
    }

    private fun handleMifareUltralightTransceive(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareUltralight.get(it) }) {
            val data = call.argument<ByteArray>("data")!!
            result.success(it.transceive(data))
        }
    }

    private fun handleSetPassword(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareUltralight.get(it) }) {
            val data = call.argument<String>("data")!!
            val pass = data.toByteArray()
            val pack = byteArrayOf(0x98.toByte(), 0x76.toByte())

//            val auth: ByteArray = it.transceive(
//                byteArrayOf(
//                    0x1B.toByte(),  // PWD_AUTH
//                    pass[0], pass[1], pass[2], pass[3]
//
//                )
//            )
//
//            if (auth != null && auth.count() >= 2) {
//                val pack: ByteArray = Arrays.copyOf(auth, 2)
//                // TODO: verify PACK to confirm that tag is authentic (not really,
//                // but that whole PWD_AUTH/PACK authentication mechanism was not
//                // really meant to bring much security, I hope; same with the
//                // NTAG signature btw.)
//            }

            val one: ByteArray = it.transceive(
                byteArrayOf(
                    0xA2.toByte(),  // WRITE
                    43.toByte(),

                    pass[0], pass[1], pass[2], pass[3]
                )
            )
            val two: ByteArray = it.transceive(
                byteArrayOf(
                    0xA2.toByte(),  // WRITE
                    44.toByte(),  // page address
                    pack[0],
                    pack[1],
                    0.toByte(),
                    0.toByte()// other bytes are RFU and must be written as 0
                )
            )
            var response: ByteArray = it.transceive(
                byteArrayOf(
                    0x30.toByte(),  // READ
                    42.toByte() // page address
                )
            )
            if (response != null && response.size >= 16) {  // read always returns 4 pages
                val prot =
                    false // false = PWD_AUTH for write only, true = PWD_AUTH for read and write
                val authlim = 0 // value between 0 and 7
                response = it.transceive(
                    byteArrayOf(
                        0xA2.toByte(),  // WRITE
                        42.toByte(),  // page address
                        ((response[0] and 0x078) or (0x000) or ((authlim and 0x007).toByte())),
                        response[1], response[2],
                        response[3] // keep old value for bytes 1-3, you could also simply set them to 0 as they are currently RFU and must always be written as 0 (response[1], response[2], response[3] will contain 0 too as they contain the read RFU value)
                    )
                )
                var response: ByteArray = it.transceive(
                    byteArrayOf(
                        0x30.toByte(),  // READ
                        41.toByte() // page address
                    )
                )
                if (response != null && response.size >= 16) {  // read always returns 4 pages
                    val prot =
                        false // false = PWD_AUTH for write only, true = PWD_AUTH for read and write
                    val auth0 =
                        0 // first page to be protected, set to a value between 0 and 37 for NTAG212
                    response = it.transceive(
                        byteArrayOf(
                            0xA2.toByte(),  // WRITE
                            41.toByte(),  // page address
                            response[0],  // keep old value for byte 0
                            response[1],  // keep old value for byte 1
                            response[2],  // keep old value for byte 2
                            (auth0 and 0x0ff).toByte()
                        )
                    )
                }

                result.success(true)
            } else {
                result.success(false)
            }
        }
    }

    private fun handleRemovePassword(call: MethodCall, result: Result) {
        tagHandler(call, result, { MifareUltralight.get(it) }) {
            val data = call.argument<String>("data")!!
            val pass = data.toByteArray()
            val pack = byteArrayOf(0x98.toByte(), 0x76.toByte())

            val auth: ByteArray = it.transceive(
                byteArrayOf(
                    0x1B.toByte(),  // PWD_AUTH
                    pass[0], pass[1], pass[2], pass[3]

                )
            )

            if (auth != null && auth.count() >= 2) {
                val pack: ByteArray = Arrays.copyOf(auth, 2)
                // TODO: verify PACK to confirm that tag is authentic (not really,
                // but that whole PWD_AUTH/PACK authentication mechanism was not
                // really meant to bring much security, I hope; same with the
                // NTAG signature btw.)
            }

            val one: ByteArray = it.transceive(
                byteArrayOf(
                    0xA2.toByte(),  // WRITE
                    43.toByte(),

                    pass[0], pass[1], pass[2], pass[3]
                )
            )
            val two: ByteArray = it.transceive(
                byteArrayOf(
                    0xA2.toByte(),  // WRITE
                    44.toByte(),  // page address
                    pack[0],
                    pack[1],
                    0.toByte(),
                    0.toByte()// other bytes are RFU and must be written as 0
                )
            )
            var response: ByteArray = it.transceive(
                byteArrayOf(
                    0x30.toByte(),  // READ
                    42.toByte() // page address
                )
            )
            if (response != null && response.size >= 16) {  // read always returns 4 pages
                val prot =
                    false // false = PWD_AUTH for write only, true = PWD_AUTH for read and write
                val authlim = 0 // value between 0 and 7
                response = it.transceive(
                    byteArrayOf(
                        0xA2.toByte(),  // WRITE
                        42.toByte(),  // page address
                        ((response[0] and 0x078) or (0x000) or ((authlim and 0x007).toByte())),
                        response[1], response[2],
                        response[3] // keep old value for bytes 1-3, you could also simply set them to 0 as they are currently RFU and must always be written as 0 (response[1], response[2], response[3] will contain 0 too as they contain the read RFU value)
                    )
                )
                var response: ByteArray = it.transceive(
                    byteArrayOf(
                        0x30.toByte(),  // READ
                        41.toByte() // page address
                    )
                )
                if (response != null && response.size >= 16) {  // read always returns 4 pages
                    val prot =
                        false // false = PWD_AUTH for write only, true = PWD_AUTH for read and write
                    val auth0 =
                        0 // first page to be protected, set to a value between 0 and 37 for NTAG212
                    response = it.transceive(
                        byteArrayOf(
                            0xA2.toByte(),  // WRITE
                            41.toByte(),  // page address
                            response[0],  // keep old value for byte 0
                            response[1],  // keep old value for byte 1
                            response[2],  // keep old value for byte 2
                            0xff.toByte()
                        )
                    )
                }

                result.success(true)
            } else {
                result.success(false)
            }
        }
    }

    private fun handleNdefFormatableFormat(call: MethodCall, result: Result) {
        tagHandler(call, result, { NdefFormatable.get(it) }) {
            val firstMessage = getNdefMessage(call.argument<Map<String, Any?>>("firstMessage")!!)
            it.format(firstMessage)
            result.success(null)
        }
    }

    private fun handleNdefFormatableFormatReadOnly(call: MethodCall, result: Result) {
        tagHandler(call, result, { NdefFormatable.get(it) }) {
            val firstMessage = getNdefMessage(call.argument<Map<String, Any?>>("firstMessage")!!)
            it.formatReadOnly(firstMessage)
            result.success(null)
        }
    }

    private fun <T : TagTechnology> tagHandler(
        call: MethodCall,
        result: Result,
        getMethod: (Tag) -> T?,
        callback: (T) -> Unit
    ) {
        val tag = tags[call.argument<String>("handle")!!] ?: run {
            result.error("invalid_parameter", "Tag is not found", null)
            return
        }

        val tech = getMethod(tag) ?: run {
            result.error("invalid_parameter", "Tech is not supported", null)
            return
        }

        try {
            forceConnect(tech)
            callback(tech)
        } catch (e: Exception) {
            result.error("io_exception", e.localizedMessage, null)
        }
    }

    @Throws(IOException::class)
    private fun forceConnect(tech: TagTechnology) {
        connectedTech?.let {
            if (it.tag == tech.tag && it::class.java.name == tech::class.java.name) return
            try {
                tech.close()
            } catch (e: IOException) { /* no op */
            }
            tech.connect()
            connectedTech = tech
        } ?: run {
            tech.connect()
            connectedTech = tech
        }
    }
}
