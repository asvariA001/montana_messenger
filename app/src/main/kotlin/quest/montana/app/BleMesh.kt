package quest.montana.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * THE BLUETOOTH MESH, part 1: the transport (iOS MontanaBLEMesh 1-897). Phones near each other find one another with no network at
 * all: each is a GATT server (a peripheral offering one service with one characteristic) and a GATT client (a central scanning for
 * that service), byte for byte with the iPhone's radio.
 *
 * On the air: a cell `[3 | type | msgId16 | dst16 | payload]` (header 34), cut into pieces `[1 | 3 | fragId8 | idx u16 BE |
 * total u16 BE | chunk]` gathered per link and fragId (20 s, at most 64 gatherings). A cell is heard once (the last 512 msgIds).
 * A relay adds its path seal (16 bytes, once, at most 32) and a count byte behind the payload.
 *  1 beacon `pos32 | kem1184 | ipLen | ip | port BE`: every 30 s and on every new link, passed on; a neighbour's sealing key for
 *    three minutes, its endpoint only when global.
 *  5 knock `pos32 | n | tag16 × n`, n fixed at four (noise pads it), once a window per conversation, never passed on;
 *  6 the answer `pos32 | kem1184`, addressed to the tag it answers, back on the link it came by.
 *  2 letter / 4 blob piece: opened in part 2; until then a cell not ours is passed on and carried (DTN, 256, two windows).
 *  7/8/9 the mesh room: part 3.
 * The position names this window only (Wire.windowPos of the device's overlay tag): nothing on the air joins two windows.
 * The person's switch «visible in the mesh» (Mesh.KEY) governs it, the same switch as the local network (iOS meshDiscoverable).
 */
@SuppressLint("MissingPermission")   // every radio call stands behind granted(): the switch asks first
object BleMesh {
    private val SERVICE: UUID = UUID.fromString("4D4F4E54-414E-4100-4245-6C654D657368")   // "MONTANA..BleMesh"
    private val CHAR: UUID = UUID.fromString("4D4F4E54-414E-4101-4D65-737361676573")      // "MONTANA..Messages"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val VERSION: Byte = 3
    private const val HEADER = 34
    private val BROADCAST = ByteArray(16)
    private const val KNOCK_TAGS = 4
    private const val NODE_TTL = 3 * 60_000L
    private const val CARRY_TTL = 2 * 60_000L
    private const val CARRY_CAP = 256
    private const val ASSEMBLY_TTL = 20_000L

    private val thread = HandlerThread("montana.ble.mesh").apply { start() }
    private val h = Handler(thread.looper)

    private var app: Context? = null
    @Volatile private var started = false
    private var kemPk = ByteArray(0)
    private var kemSk = ByteArray(0)
    private var overlay = ByteArray(0)

    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var scanning = false
    private var dutyOn = false

    /** A link: a phone this one writes to (central role) or a phone subscribed to this one (peripheral role). */
    private class Out(val gatt: BluetoothGatt) { var char: BluetoothGattCharacteristic? = null; var mtu = 23; var busy = false; var ready = false; val q = ArrayDeque<ByteArray>() }
    private class In(val dev: BluetoothDevice) { var mtu = 23; var busy = false; val q = ArrayDeque<ByteArray>() }
    private val outs = HashMap<String, Out>()       // by address: peripherals we write to
    private val ins = HashMap<String, In>()         // by address: centrals subscribed to us
    private val pending = HashSet<String>()

    private class Peer(val kem: ByteArray, val ref: String)
    private val peers = HashMap<String, Peer>()             // by position hex
    private val nodeKem = HashMap<String, ByteArray>()
    private val nodeEndpoint = HashMap<String, Pair<String, Int>>()
    private val nodeSeenAt = HashMap<String, Long>()
    @Volatile private var peerRefs: List<String> = emptyList()

    private val seen = ArrayDeque<String>()
    private val seenSet = HashSet<String>()
    private val assembly = HashMap<String, Pair<Int, HashMap<Int, ByteArray>>>()
    private val assemblyAt = HashMap<String, Long>()

    private class Carried(val type: Int, val dst: ByteArray, val payload: ByteArray, val expiry: Long)
    private val carryStore = HashMap<String, Carried>()

    private var knockedWindow = -1L
    private val knockedSet = HashSet<String>()
    private var heardWindow = -1L
    private val heardSet = HashSet<String>()

    // ---------------------------------------------------------------- the switch, the permissions, the start

    /** The radio's permissions of this Android: SCAN/ADVERTISE/CONNECT from 12, before it the old pair and the location a scan needs. */
    fun perms(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(c: Context): Boolean = perms().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Links the radio holds right now (iOS linkCount): a neighbour connected, not merely Bluetooth on. */
    val linkCount: Int get() = synchronized(outs) { outs.values.count { it.ready } + ins.size }
    /** The conversations a neighbour of the radio answered for (iOS setBTPeers): a live road for the word about myself. */
    fun nearRefs(): List<String> = peerRefs

    fun start(c: Context) {
        app = c.applicationContext
        h.post { up() }
    }

    /** The switch (iOS setDiscoverable): on offers this phone and scans; off goes quiet and forgets the neighbours. */
    fun setDiscoverable(on: Boolean) = h.post { if (on) up() else powerDown() }

    private fun up() {
        val c = app ?: return
        if (started || !Mesh.discoverable) return
        if (!granted(c)) { Log.d("Montana", "ble_off why=permission"); return }
        val bm = c.getSystemService(BluetoothManager::class.java) ?: return
        val ad = bm.adapter ?: return Unit.also { Log.d("Montana", "ble_off why=no_adapter") }
        if (!ad.isEnabled) { Log.d("Montana", "ble_off why=radio_off"); return }
        // the node's sealing key and its overlay tag: drawn for the device, never from the seed (iOS start 71-101)
        val kem = NodeKem.device() ?: return Unit.also { Log.d("Montana", "ble_off why=kem") }
        val ident = NodeIdentity.device() ?: return Unit.also { Log.d("Montana", "ble_off why=identity") }
        kemPk = kem.first; kemSk = kem.second; overlay = ident.tag
        started = true
        openServer(c, bm)
        advertise(ad)
        duty()
        h.postDelayed(beat, 2_000)
        Log.d("Montana", "ble_start node=" + Wire.hex(overlay.copyOf(4)))
    }

    /** iOS powerDown: everything stops and the neighbours are forgotten; the keys stay with the device. */
    private fun powerDown() {
        if (!started) return
        started = false
        h.removeCallbacks(beat); h.removeCallbacks(dutyTick)
        val ad = app?.getSystemService(BluetoothManager::class.java)?.adapter
        runCatching { if (scanning) ad?.bluetoothLeScanner?.stopScan(scanCb) }; scanning = false
        runCatching { if (advertising) ad?.bluetoothLeAdvertiser?.stopAdvertising(advCb) }; advertising = false
        synchronized(outs) {
            for (o in outs.values) runCatching { o.gatt.disconnect(); o.gatt.close() }
            outs.clear(); ins.clear(); pending.clear()
        }
        runCatching { server?.close() }; server = null; serverChar = null
        peers.clear(); publishPeers(); carryStore.clear(); assembly.clear(); assemblyAt.clear()
        Log.d("Montana", "ble_power_down")
    }

    // ---------------------------------------------------------------- the peripheral: the service, the offer

    private fun openServer(c: Context, bm: BluetoothManager) {
        val s = bm.openGattServer(c, serverCb) ?: return Unit.also { Log.d("Montana", "ble_server_failed") }
        val ch = BluetoothGattCharacteristic(CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        ch.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(ch) }
        s.addService(svc)
        server = s; serverChar = ch
    }

    private fun advertise(ad: android.bluetooth.BluetoothAdapter) {
        val adv = ad.bluetoothLeAdvertiser ?: return Unit.also { Log.d("Montana", "ble_advertise_unsupported") }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE)).build()
        runCatching { adv.startAdvertising(settings, data, advCb); advertising = true }
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { advertising = false; Log.d("Montana", "ble_advertise_failed code=$errorCode") }
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_DISCONNECTED) h.post { synchronized(outs) { ins.remove(d.address) } }
        }
        override fun onMtuChanged(d: BluetoothDevice, mtu: Int) { h.post { synchronized(outs) { ins[d.address]?.mtu = mtu }; mtuOf[d.address] = mtu } }
        override fun onCharacteristicWriteRequest(d: BluetoothDevice, id: Int, ch: BluetoothGattCharacteristic, prepared: Boolean, needs: Boolean, offset: Int, value: ByteArray?) {
            if (needs) runCatching { server?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, null) }
            val v = value ?: return
            if (prepared) { synchronized(prep) { prep.getOrPut(d.address) { java.io.ByteArrayOutputStream() }.write(v) }; return }   // a long write's part
            h.post { onFragment(v, "in:" + d.address) }
        }
        override fun onExecuteWrite(d: BluetoothDevice, id: Int, execute: Boolean) {
            runCatching { server?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, null) }
            val v = synchronized(prep) { prep.remove(d.address) }?.toByteArray() ?: return
            if (execute) h.post { onFragment(v, "in:" + d.address) }
        }
        override fun onDescriptorWriteRequest(d: BluetoothDevice, id: Int, desc: BluetoothGattDescriptor, prepared: Boolean, needs: Boolean, offset: Int, value: ByteArray?) {
            if (needs) runCatching { server?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, null) }
            if (desc.uuid != CCCD) return
            val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
            h.post {
                synchronized(outs) { if (on) ins.getOrPut(d.address) { In(d).also { it.mtu = mtuOf[d.address] ?: 23 } } else ins.remove(d.address) }
                if (on) announce()   // a neighbour subscribed: it learns this window's key at once (iOS didSubscribeTo)
            }
        }
        override fun onNotificationSent(d: BluetoothDevice, status: Int) { h.post { synchronized(outs) { ins[d.address]?.busy = false }; drainIn(d.address) } }
    }
    private val mtuOf = HashMap<String, Int>()
    private val prep = HashMap<String, java.io.ByteArrayOutputStream>()

    // ---------------------------------------------------------------- the central: the scan, the links

    /** The scan in a duty cycle (iOS startDutyScan): four seconds on, four off; Android allows five starts in thirty seconds. */
    private fun duty() { h.removeCallbacks(dutyTick); h.post(dutyTick) }
    private val dutyTick: Runnable = object : Runnable {
        override fun run() {
            if (!started) return
            val sc = app?.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            if (sc != null) {
                dutyOn = !dutyOn
                runCatching {
                    if (dutyOn) {
                        val f = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
                        sc.startScan(listOf(f), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb); scanning = true
                    } else { sc.stopScan(scanCb); scanning = false }
                }
            }
            h.postDelayed(this, 4_000)
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) { h.post { found(r.device) } }
        override fun onBatchScanResults(rs: MutableList<ScanResult>) { h.post { rs.forEach { found(it.device) } } }
        override fun onScanFailed(code: Int) { Log.d("Montana", "ble_scan_failed code=$code") }
    }

    private fun found(d: BluetoothDevice) {
        val c = app ?: return
        if (!started) return
        synchronized(outs) { if (d.address in pending || outs.containsKey(d.address)) return; pending.add(d.address) }
        val g = if (Build.VERSION.SDK_INT >= 26) d.connectGatt(c, false, gattCb, BluetoothDevice.TRANSPORT_LE) else d.connectGatt(c, false, gattCb)
        if (g == null) synchronized(outs) { pending.remove(d.address) }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            val a = g.device.address
            if (state == BluetoothProfile.STATE_CONNECTED) {
                h.post { synchronized(outs) { outs.getOrPut(a) { Out(g) } }; if (!g.requestMtu(517)) g.discoverServices() }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                h.post { synchronized(outs) { outs.remove(a); pending.remove(a) }; runCatching { g.close() } }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            h.post { synchronized(outs) { outs[g.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23 }; g.discoverServices() }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(CHAR) ?: return Unit.also { h.post { synchronized(outs) { pending.remove(g.device.address) } }; runCatching { g.disconnect() } }
            h.post {
                val o = synchronized(outs) { outs[g.device.address] } ?: return@post
                o.char = ch
                g.setCharacteristicNotification(ch, true)
                val desc = ch.getDescriptor(CCCD)
                o.busy = true
                val ok = desc != null && if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                    else @Suppress("DEPRECATION") run { desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(desc) }
                if (!ok) linked(o)
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post { synchronized(outs) { outs[g.device.address] }?.let { linked(it) } }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            h.post { synchronized(outs) { outs[g.device.address] }?.let { it.busy = false; drainOut(it) } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf(); h.post { onFragment(v, "out:" + g.device.address) }
        }
        @Deprecated("before 13") override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // a second copy of a piece where both callbacks run: it fills the same slot of the same gathering, and a whole cell is heard once
            @Suppress("DEPRECATION") val v = ch.value?.copyOf() ?: return
            h.post { onFragment(v, "out:" + g.device.address) }
        }
    }

    /** A link stands: the subscription is written, the queue may flow, and the neighbour learns this window's key (iOS 856-862). */
    private fun linked(o: Out) {
        o.busy = false
        if (!o.ready) { o.ready = true; synchronized(outs) { pending.remove(o.gatt.device.address) }; Log.d("Montana", "ble_link links=$linkCount") }
        drainOut(o)
        announce()
    }

    // ---------------------------------------------------------------- the cell, the pieces, the queues

    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun random(n: Int) = MtBindings.nativeRandom(n) ?: ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun packet(type: Int, msgId: ByteArray, dst: ByteArray, payload: ByteArray): ByteArray =
        byteArrayOf(VERSION, type.toByte()) + msgId + dst.copyOf(16) + payload

    private class Cell(val type: Int, val msgId: ByteArray, val dst: ByteArray, val payload: ByteArray)
    private fun parse(d: ByteArray): Cell? {
        if (d.size < HEADER || d[0] != VERSION) return null
        return Cell(d[1].toInt() and 0xff, d.copyOfRange(2, 18), d.copyOfRange(18, 34), d.copyOfRange(34, d.size))
    }

    /** A cell cut to the link's measure (iOS fragments): a piece never longer than what one write carries. */
    private fun fragments(p: ByteArray, room: Int): List<ByteArray> {
        val chunk = maxOf(64, room - 14)
        val id = random(8)
        val total = (p.size + chunk - 1) / chunk
        val out = ArrayList<ByteArray>(total)
        var off = 0; var idx = 0
        while (off < p.size) {
            val end = minOf(off + chunk, p.size)
            out.add(byteArrayOf(1, 3) + id + byteArrayOf((idx shr 8).toByte(), idx.toByte(), (total shr 8).toByte(), total.toByte()) + p.copyOfRange(off, end))
            idx++; off = end
        }
        return out
    }

    private fun reassemble(f: ByteArray, link: String): ByteArray? {
        if (f.size < 14 || f[0].toInt() != 1 || f[1].toInt() != 3) return null
        val idx = ((f[10].toInt() and 0xff) shl 8) or (f[11].toInt() and 0xff)
        val total = ((f[12].toInt() and 0xff) shl 8) or (f[13].toInt() and 0xff)
        if (total == 0 || idx >= total) return null
        val key = link + "-" + Wire.hex(f.copyOfRange(2, 10))
        val now = System.currentTimeMillis()
        assemblyAt.entries.removeAll { (k, t) -> (now - t > ASSEMBLY_TTL).also { if (it) assembly.remove(k) } }
        if (assembly.size > 64) assemblyAt.minByOrNull { it.value }?.key?.let { assembly.remove(it); assemblyAt.remove(it) }
        val e = assembly.getOrPut(key) { total to HashMap() }
        e.second[idx] = f.copyOfRange(14, f.size); assemblyAt[key] = now
        if (e.second.size != e.first) return null
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until e.first) out.write(e.second[i] ?: return null)
        assembly.remove(key); assemblyAt.remove(key)
        return out.toByteArray()
    }

    private fun onFragment(f: ByteArray, link: String) { reassemble(f, link)?.let { handle(it, link) } }

    private fun enqueueOut(o: Out, p: ByteArray) {
        o.q.addAll(fragments(p, o.mtu - 3))
        while (o.q.size > 512) o.q.removeFirst()
        drainOut(o)
    }

    /** One write at a time per link: Android takes the next only after the last is answered (iOS canSendWriteWithoutResponse). */
    private fun drainOut(o: Out) {
        if (!o.ready || o.busy) return
        val ch = o.char ?: return
        val f = o.q.removeFirstOrNull() ?: return
        // a write longer than the link's measure goes as a long write with an answer; the rest without one, as iOS writes
        val type = if (f.size > o.mtu - 3) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        o.busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) o.gatt.writeCharacteristic(ch, f, type) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = f; o.gatt.writeCharacteristic(ch) }
        if (!ok) { o.busy = false; o.q.addFirst(f); h.postDelayed({ drainOut(o) }, 50) }
    }

    private fun enqueueIn(i: In, p: ByteArray) {
        i.q.addAll(fragments(p, i.mtu - 3))
        while (i.q.size > 512) i.q.removeFirst()
        drainIn(i.dev.address)
    }

    private fun drainIn(addr: String) {
        val i = synchronized(outs) { ins[addr] } ?: return
        val s = server ?: return; val ch = serverChar ?: return
        if (i.busy) return
        val f = i.q.removeFirstOrNull() ?: return
        i.busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(i.dev, ch, false, f) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { ch.value = f; s.notifyCharacteristicChanged(i.dev, ch, false) }
        if (!ok) { i.busy = false; i.q.addFirst(f); h.postDelayed({ drainIn(addr) }, 50) }
    }

    /** iOS sendOnLink: back on the one link a cell came by; with no link, to everyone. */
    private fun sendOnLink(p: ByteArray, link: String?) {
        if (link == null) return broadcast(p, null)
        val addr = link.substringAfter(':')
        synchronized(outs) {
            if (link.startsWith("out:")) outs[addr]?.let { return enqueueOut(it, p) }
            ins[addr]?.let { return enqueueIn(it, p) }
        }
    }

    /** iOS broadcast: every link but the one it came by. */
    private fun broadcast(p: ByteArray, except: String?) {
        val (o, i) = synchronized(outs) { outs.values.filter { it.ready }.toList() to ins.values.toList() }
        for (x in o) if (except != "out:" + x.gatt.device.address) enqueueOut(x, p)
        for (x in i) if (except != "in:" + x.dev.address) enqueueIn(x, p)
    }

    private fun markSeen(id: ByteArray) {
        val k = Wire.hex(id)
        if (!seenSet.add(k)) return
        seen.addLast(k)
        if (seen.size > 512) seenSet.remove(seen.removeFirst())
    }

    // ---------------------------------------------------------------- the seal of a letter to a node, the path seals

    /** ct1088 ‖ the blob sealed under the shared secret (iOS seal(to:)): only the node that holds the key opens it. */
    fun seal(kem: ByteArray, msg: ByteArray): ByteArray? {
        val e = MtBindings.nativeMlkemEncaps(kem)?.takeIf { it.size == 1088 + 32 } ?: return null
        val sealed = MtBindings.nativeSealBlob(e.copyOfRange(1088, 1120), random(12), msg) ?: return null
        return e.copyOfRange(0, 1088) + sealed
    }

    /** iOS open: this node's key opens a cell sealed to it; a foreign cell yields nothing. */
    fun open(blob: ByteArray): ByteArray? {
        if (blob.size <= 1088 || kemSk.isEmpty()) return null
        val ss = MtBindings.nativeMlkemDecaps(kemSk, blob.copyOfRange(0, 1088))?.takeIf { it.size == 32 } ?: return null
        return MtBindings.nativeOpenBlob(ss, blob.copyOfRange(1088, blob.size))
    }

    fun seals(payload: ByteArray): List<ByteArray> {
        val n = payload.lastOrNull()?.toInt()?.and(0xff) ?: return emptyList()
        if (n == 0 || payload.size <= 1 + n * 16) return emptyList()
        val start = payload.size - 1 - n * 16
        return (0 until n).map { payload.copyOfRange(start + it * 16, start + it * 16 + 16) }
    }

    fun body(payload: ByteArray): ByteArray {
        val n = payload.lastOrNull()?.toInt()?.and(0xff) ?: return payload
        if (n == 0 || payload.size <= 1 + n * 16) return payload
        return payload.copyOf(payload.size - 1 - n * 16)
    }

    fun distinctOwners(payload: ByteArray): Int = seals(payload).map { Wire.hex(it) }.toSet().size

    /** iOS withSeal: this owner's seal of the step, once, at most 32 — how far a cell travelled, and through how many people. */
    private fun withSeal(payload: ByteArray, step: ByteArray): ByteArray {
        val owner = Channels.ownerKey() ?: return payload
        val seal = Wire.relaySeal(owner, step)
        val list = seals(payload)
        if (list.any { it.contentEquals(seal) } || list.size >= 32) return payload
        val out = java.io.ByteArrayOutputStream()
        out.write(body(payload)); (list + listOf(seal)).forEach { out.write(it) }; out.write(list.size + 1)
        return out.toByteArray()
    }

    private fun relay(c: Cell, link: String?) = broadcast(packet(c.type, c.msgId, c.dst, withSeal(c.payload, c.msgId)), link)

    /** DTN (iOS carry): a cell for somebody not here yet is held two windows, at most 256. */
    private fun carry(c: Cell) {
        val k = Wire.hex(c.msgId)
        if (c.dst.contentEquals(BROADCAST) || carryStore.containsKey(k)) return
        val now = System.currentTimeMillis()
        carryStore.entries.removeAll { it.value.expiry <= now }
        if (carryStore.size >= CARRY_CAP) carryStore.minByOrNull { it.value.expiry }?.key?.let { carryStore.remove(it) }
        carryStore[k] = Carried(c.type, c.dst, c.payload, now + CARRY_TTL)
        Log.d("Montana", "ble_carry held=" + carryStore.size)
    }

    /** iOS flushCarry: the conversation's neighbour came, what was held for its tags goes out. */
    private fun flushCarry(conv: String) {
        val now = System.currentTimeMillis()
        carryStore.entries.removeAll { it.value.expiry <= now }
        val s = Book.secret(conv) ?: return
        val w = Wire.minute()
        val tags = HashSet<String>()
        for (k in longArrayOf(w - 1, w, w + 1)) Wire.windowTags(s, k, Book.healing(conv)).forEach { tags.add(Wire.hex(it)) }
        val it = carryStore.entries.iterator()
        while (it.hasNext()) {
            val (mid, c) = it.next()
            if (Wire.hex(c.dst) !in tags) continue
            broadcast(packet(c.type, unhex(mid), c.dst, c.payload), null)
            it.remove()
            Log.d("Montana", "ble_carry_flush delivered")
        }
    }

    // ---------------------------------------------------------------- the beacon, the knock, the answer

    private fun pos(w: Long = Wire.minute()) = Wire.windowPos(overlay, w)
    private fun mine(node: ByteArray) = Wire.myPositions(overlay).any { it.contentEquals(node) }

    /** Every 30 s and at each new link (iOS startAnnounce): the stale neighbours go, the beacon goes out. */
    private val beat: Runnable = object : Runnable {
        override fun run() {
            if (!started) return
            val cut = System.currentTimeMillis() - NODE_TTL
            nodeSeenAt.entries.removeAll { (k, t) -> (t < cut).also { if (it) { nodeKem.remove(k); nodeEndpoint.remove(k) } } }
            announce()
            h.postDelayed(this, 30_000)
        }
    }

    /** iOS announce: this window's position, the node's sealing key, and an endpoint only a global address may name. */
    private fun announce() {
        if (!started || !Mesh.discoverable) return
        val ip = Channels.publicEndpoint().orEmpty().toByteArray().let { it.copyOf(minOf(it.size, 255)) }
        val port = if (ip.isEmpty()) 0 else Channels.PORT
        val payload = pos() + kemPk + byteArrayOf(ip.size.toByte()) + ip + byteArrayOf((port shr 8).toByte(), port.toByte())
        val id = random(16)
        markSeen(id); broadcast(packet(1, id, BROADCAST, payload), null)
    }

    /** iOS seek → knock: the tags of the conversations waiting for a road, four at most, padded with noise, once a window. */
    fun seek(convs: List<String>) = h.post { knock(convs) }

    private fun knock(convs: List<String>) {
        if (!started || !Mesh.discoverable || convs.isEmpty()) return
        val w = Wire.minute()
        if (w != knockedWindow) { knockedWindow = w; knockedSet.clear() }
        if (w != heardWindow) { heardWindow = w; heardSet.clear() }
        val want = convs.take(KNOCK_TAGS).toSet() - heardSet
        if (want.isEmpty() || knockedSet.containsAll(want)) return
        knockedSet.addAll(want)
        val tags = want.mapNotNull { c -> Book.secret(c)?.let { Wire.windowTag(it, w, Book.healing(c)) } }.toMutableList()
        if (tags.isEmpty()) return
        while (tags.size < KNOCK_TAGS) tags.add(random(16))   // the count of tags says nothing
        val payload = java.io.ByteArrayOutputStream().apply { write(pos(w)); write(tags.size); tags.forEach { write(it) } }.toByteArray()
        val id = random(16)
        markSeen(id); broadcast(packet(5, id, BROADCAST, payload), null)
        Log.d("Montana", "ble_knock tags=" + tags.size)
    }

    private fun answerKnock(tag: ByteArray, link: String?) {
        val id = random(16)
        sendOnLink(packet(6, id, tag, pos() + kemPk), link)
        markSeen(id)
    }

    private fun publishPeers() { peerRefs = peers.values.map { it.ref }.filter { it.isNotEmpty() }.distinct() }

    // ---------------------------------------------------------------- a whole cell heard

    private fun handle(d: ByteArray, link: String) {
        val c = parse(d) ?: return
        val idk = Wire.hex(c.msgId)
        if (idk in seenSet) return   // heard once — and it is what bounds the flood
        markSeen(c.msgId)
        val p = c.payload
        when (c.type) {
            1 -> {   // beacon
                if (p.size < 32 + 1184 + 3) return
                val node = p.copyOfRange(0, 32); val kem = p.copyOfRange(32, 32 + 1184)
                var o = 32 + 1184
                val il = p[o].toInt() and 0xff; o++
                if (p.size < o + il + 2) return
                val ip = String(p, o, il, Charsets.UTF_8); o += il
                val port = ((p[o].toInt() and 0xff) shl 8) or (p[o + 1].toInt() and 0xff)
                if (mine(node)) return
                val nk = Wire.hex(node)
                nodeSeenAt[nk] = System.currentTimeMillis(); nodeKem[nk] = kem
                peers[nk]?.takeIf { it.kem.size != 1184 }?.let { peers[nk] = Peer(kem, it.ref); publishPeers() }
                if (ip.isNotEmpty() && port > 0 && Channels.isGlobal(ip)) nodeEndpoint[nk] = ip to port
                relay(c, link)   // a beacon travels the mesh so sealing keys are known beyond one hop
            }
            5 -> {   // knock
                if (p.size < 33) return
                val node = p.copyOfRange(0, 32); val n = p[32].toInt() and 0xff
                if (n == 0 || p.size < 33 + n * 16 || mine(node)) return
                val nk = Wire.hex(node)
                for (k in 0 until n) {
                    val tag = p.copyOfRange(33 + k * 16, 49 + k * 16)
                    val conv = Channels.pipeOf(tag)
                    if (conv == null) { if (Channels.isFirstPoint(tag)) answerKnock(tag, link); continue }
                    peers[nk] = Peer(nodeKem[nk] ?: ByteArray(0), conv); publishPeers()
                    val w = Wire.minute()
                    if (w != heardWindow) { heardWindow = w; heardSet.clear() }
                    heardSet.add(conv)
                    flushCarry(conv)
                    Log.d("Montana", "ble_knock_rx conv=" + conv.take(10))
                    answerKnock(tag, link)
                }
            }
            6 -> {   // the answer to a knock, addressed by the tag it answers
                if (p.size < 32 + 1184) return
                val conv = Channels.pipeOf(c.dst) ?: return   // a first contact's answer: part 2 (iOS convAwaiting)
                val node = p.copyOfRange(0, 32)
                if (mine(node)) return
                val nk = Wire.hex(node); val kem = p.copyOfRange(32, 32 + 1184)
                nodeKem[nk] = kem; peers[nk] = Peer(kem, conv); publishPeers()
                flushCarry(conv)
                Log.d("Montana", "ble_knock_answer_rx conv=" + conv.take(10))
            }
            2, 4 -> {   // a letter or a blob piece: ours is opened in part 2; a stranger's is passed on and carried
                if (Channels.pipeOf(c.dst) != null || Channels.isFirstPoint(c.dst)) {
                    Log.d("Montana", "ble_rx type=${c.type} owners=" + distinctOwners(p) + " held=part2")
                    return
                }
                relay(c, link); carry(c)
            }
            else -> {}   // 7/8/9 the mesh room: part 3
        }
    }
}
