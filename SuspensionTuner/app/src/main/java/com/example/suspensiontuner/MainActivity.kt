package com.example.suspensiontuner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import com.example.suspensiontuner.databinding.ActivityMainBinding
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.LegendRenderer.LegendAlign
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import java.lang.System.currentTimeMillis
import java.text.NumberFormat
import kotlin.math.pow
import kotlin.math.sqrt


class MainActivity : AppCompatActivity(), SensorEventListener {
    lateinit var sensorManager: SensorManager
    lateinit var accelSensor: Sensor

    private lateinit var phoneAvg: MovingAverage
    private lateinit var arduinoAvg: MovingAverage
    private lateinit var phoneRun: RunningAverage
    private lateinit var ardRun: RunningAverage


    private lateinit var acceleration: LineGraphSeries<DataPoint>
    private lateinit var arduinoAcceleration: LineGraphSeries<DataPoint>
    private lateinit var mGraphAcceleration: GraphView
    private lateinit var viewBinding: ActivityMainBinding
    private var record = 0
    var startTime = currentTimeMillis()
    var elapsedTime : Long = 0
    val gravity = 9.81

    //Bluetooth setup
    var bluetoothManager: BluetoothManager? = null
    var bluetoothAdapter: BluetoothAdapter? = null
    private val REQUEST_ENABLE_BT: Int = 42
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler()
    var blutoothGatt: BluetoothGatt? = null
    private lateinit var binding: ActivityMainBinding
    private val SCAN_PERIOD: Long = 10000
    private lateinit var myBtDeviceListAdapter: BluetoothAdapter
    private var selectedDevice: BluetoothDevice? = null
    var bluetoothGatt: BluetoothGatt? = null
    var ignoreCallback = false
    var arduinoAccel = 0.0
    private val TAG = "MainActivity"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        phoneAvg = MovingAverage(20)
        arduinoAvg = MovingAverage( 5)
        phoneRun = RunningAverage()
        ardRun = RunningAverage()
        mGraphAcceleration = viewBinding.graph
        acceleration = LineGraphSeries()
        arduinoAcceleration = LineGraphSeries()
        arduinoAcceleration.color = Color.GREEN
        initGraphRT(mGraphAcceleration,acceleration!!, arduinoAcceleration!!)
        viewBinding.graphButton.setOnClickListener { graph() }
        viewBinding.dataButton.setOnClickListener { data() }
        viewBinding.startButton.setOnClickListener { start() }
        viewBinding.stopButton.setOnClickListener { stop() }

        //Bluetooth setup
        // Check permissions in the onCreate of your main Activity
        ActivityCompat.requestPermissions(this,
            arrayOf( Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)

        // Get Bluetooth stuff
        this.bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = this.bluetoothManager!!.getAdapter()
        bluetoothScanner = bluetoothAdapter?.bluetoothLeScanner

        viewBinding.scan.setOnClickListener { scan() }
        viewBinding.connect.setOnClickListener { connect() }
        viewBinding.disconnect.setOnClickListener { disconnect() }
        viewBinding.connect.isEnabled = false
        viewBinding.disconnect.isEnabled = false
        viewBinding.textViewStatus.text = "Press Scan to find Bluefruit"

        // Check that bluetooth is available
        if (bluetoothAdapter != null) {
            if (!bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        // If we have no bluetooth, don't scan for BT devices
        if (bluetoothAdapter == null){
            viewBinding.scan.isEnabled = false
        }
        viewBinding.graph.visibility = View.VISIBLE
        viewBinding.phoneAvg.visibility = View.INVISIBLE
        viewBinding.ardAvg.visibility = View.INVISIBLE
        viewBinding.phoneMax.visibility = View.INVISIBLE
        viewBinding.ardMax.visibility = View.INVISIBLE
    }

    private fun data() {
        viewBinding.graph.visibility = View.INVISIBLE
        viewBinding.phoneAvg.visibility = View.VISIBLE
        viewBinding.ardAvg.visibility = View.VISIBLE
        viewBinding.phoneMax.visibility = View.VISIBLE
        viewBinding.ardMax.visibility = View.VISIBLE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                    "Got permissions",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun scan() {
        if (!scanning) {
            viewBinding.textViewStatus.text = "Scannning for LE devices"
            viewBinding.scan.text = "Scanning..."
            viewBinding.scan.isEnabled = false
            ignoreCallback = false
            // Stops scanning after a pre-defined scan period.
            handler.postDelayed(stopLeScan(), SCAN_PERIOD)
            // Start the scan
            scanning = true
            bluetoothScanner?.startScan(leScanCallback)
        } else {
            // Will hit here if we are already scanning
            viewBinding.textViewStatus.text = "hmm..."
            scanning = false
            bluetoothScanner?.stopScan(leScanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopLeScan() = {
        if(scanning) {
            scanning = false
            bluetoothScanner?.stopScan(leScanCallback)
            viewBinding.textViewStatus.text = "Scan completed. Bluefruit not found."
            viewBinding.scan.text = "Scan"
            viewBinding.scan.isEnabled = true
        }
    }

    // Device scan callback.
    private val leScanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if(result.device.name == "Adafruit Bluefruit LE")
            {
                Log.d("Main_Activity","Found bluetooth temp")
                selectedDevice = result.device
                viewBinding.connect.isEnabled = true
                viewBinding.textViewStatus.text = "Bluefruit found!!!"
                scanning = false
                viewBinding.textViewStatus.text = "Scan completed. Bluefruit found."
                viewBinding.scan.text = "Scan"
                viewBinding.scan.isEnabled = true
                ignoreCallback = true
            }
            else if(!ignoreCallback){
                viewBinding.textViewStatus.text = "Scanning for Bluefruit"
            }
            //myBtDeviceListAdapter.addDevice(result.device)
            Log.d("MainActivity", result.device.toString())
        }
    }

    private fun connect() {
        if (selectedDevice != null) {
            viewBinding.textViewStatus.text = "Connecting to device: " + selectedDevice!!.name
            bluetoothAdapter?.let { adapter ->
                try {
                    val device = adapter.getRemoteDevice(selectedDevice!!.address)
                    // connect to the GATT server on the device
                    bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
                    viewBinding.connect.isEnabled = false
                    viewBinding.disconnect.isEnabled = true
                    return
                } catch (exception: IllegalArgumentException) {
                    Log.w(TAG, "Device not found with provided address.  Unable to connect.")
                    return
                }
            } ?: run {
                Log.w(TAG, "BluetoothAdapter not initialized")
                return
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        bluetoothGatt?.disconnect()
        viewBinding.disconnect.isEnabled = false
        viewBinding.connect.isEnabled = true
        viewBinding.textViewStatus.text = "Disconnected from device"
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                Log.i(TAG, "Starting service discovery")
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered: ")
                Log.i(TAG, gatt?.services.toString())
                checkAndConnectToHRM(bluetoothGatt?.services)
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            // CircuitPlayground is sending 2 bytes
            // value[0] is "Sensor Connected"
            // value[1] is the current beats per second
            Log.i(TAG, characteristic?.value?.get(1)?.toUByte().toString())
            handler.post {
                viewBinding.textViewStatus.text =
                    "Arduino Connected"
                val intAccel = characteristic?.value?.get(1)?.toUByte()?.toInt()
                if (intAccel != null) {
                    arduinoAccel = (intAccel.toDouble()*30/255)
                    arduinoAvg.pushValue(arduinoAccel)
                    ardRun.addValue(arduinoAccel-gravity+.71)
                    if(record==1) {
                            viewBinding.ardAvg.setText("Arduino Average Accel: " + String.format("%.2f", makeZero(ardRun.value)))
                            viewBinding.phoneAvg.setText("Phone Average Accel: " + String.format("%.2f", makeZero(phoneRun.value)))
                            viewBinding.phoneMax.setText("Phone Max Accel:" + String.format("%.2f", makeZero(phoneRun.max)))
                            viewBinding.ardMax.setText("Arduino Max Accel:" + String.format("%.2f", makeZero(ardRun.max)))
                    }



                }
            }
            val xval = (currentTimeMillis()+elapsedTime - startTime) / 1000.toDouble()//graphLastXValue += 0.1
            if(record == 1) {
                acceleration!!.appendData(
                    DataPoint(
                        xval,
                        phoneAvg.value - gravity
                    ), true, 250
                )
                arduinoAcceleration!!.appendData(
                    DataPoint(
                        xval,
                        arduinoAvg.value - gravity +.71
                    ), true, 250
                )
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun checkAndConnectToHRM(services: List<BluetoothGattService>?) {
        Log.i(TAG, "Checking for HRM Service")
        services?.forEach { service ->
            if (service.uuid == SampleGattAttributes.HEART_RATE_SERVICE_UUID){
                Log.i(TAG, "Found HRM Service")
                val characteristic = service.getCharacteristic(SampleGattAttributes.HEART_RATE_MEASUREMENT_UUID)
                bluetoothGatt?.readCharacteristic(characteristic)

                // First, call setCharacteristicNotification to enable notification.
                if (!bluetoothGatt?.setCharacteristicNotification(characteristic, true)!!) {
                    // Stop if the characteristic notification setup failed.
                    Log.e("BLE", "characteristic notification setup failed")
                    return
                }
                // Then, write a descriptor to the btGatt to enable notification
                val descriptor =
                    characteristic.getDescriptor(SampleGattAttributes.CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID)
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGatt!!.writeDescriptor(descriptor)
                // When the characteristic value changes, the Gatt callback will be notified
            }
        }
    }

    private fun stop() {
        record = 0
        elapsedTime += (currentTimeMillis() - startTime)
    }

    private fun start() {
        record = 1
        startTime = currentTimeMillis()
        phoneAvg.reset()
        arduinoAvg.reset()
        phoneRun.reset()
        ardRun.reset()

    }

    private fun graph() {
        viewBinding.graph.visibility = View.VISIBLE
        viewBinding.phoneAvg.visibility = View.INVISIBLE
        viewBinding.ardAvg.visibility = View.INVISIBLE
        viewBinding.phoneMax.visibility = View.INVISIBLE
        viewBinding.ardMax.visibility = View.INVISIBLE
    }

    override fun onSensorChanged(event: SensorEvent) {
        Log.i("SensorClassActivity", "Got Sensor update")
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER)
                return


            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                Log.i("SensorClassAct", event.values.toString())
            }

            // Update graph
            val xval = (currentTimeMillis()+elapsedTime - startTime) / 1000.toDouble()//graphLastXValue += 0.1
            val magnitude = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2)).toDouble()
            phoneAvg.pushValue(magnitude)
            phoneRun.addValue(phoneAvg.value-gravity)


    }

    override fun onResume(){
        super.onResume()
        accelSensor?.also {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause(){
        super.onPause()
        sensorManager.unregisterListener(this)
    }
    private fun initGraphRT(mGraph: GraphView, mSeriesXaccel :LineGraphSeries<DataPoint>, mSeriesYaccel :LineGraphSeries<DataPoint>){
        mGraph.getViewport().setXAxisBoundsManual(true)
        mGraph.getViewport().setMinX(0.0)
        mGraph.getViewport().setMaxX(10.0)
        mGraph.getViewport().setYAxisBoundsManual(true);

        mGraph.getViewport().setMinY(0.0);
        mGraph.getViewport().setMaxY(10.0);
        mGraph.getGridLabelRenderer().setLabelVerticalWidth(50)

        // first mSeries is a line
        mSeriesXaccel.setDrawDataPoints(false)
        mSeriesXaccel.setDrawBackground(false)
        mGraph.addSeries(mSeriesXaccel)
        mGraph.addSeries(mSeriesYaccel)
        mGraph.viewport.isScalable = true
        mGraph.viewport.isScrollable = true


        setLabelsFormat(mGraph,2,2)
    }


    fun setLabelsFormat(mGraph:GraphView,maxInt:Int,maxFraction:Int) {
        val nf = NumberFormat.getInstance()
        nf.setMaximumFractionDigits(maxFraction)
        nf.setMaximumIntegerDigits(maxInt)

        mGraph.getGridLabelRenderer().setVerticalAxisTitle("Accel m/s^2")
        mGraph.getGridLabelRenderer().setHorizontalAxisTitle("Time")

        mGraph.getGridLabelRenderer().setLabelFormatter(object : DefaultLabelFormatter(nf, nf) {
            override fun formatLabel(value: Double, isValueX: Boolean): String {
                return if (isValueX) {
                    super.formatLabel(value, isValueX) + "s"
                } else {
                    super.formatLabel(value, isValueX)
                }
            }
        })
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        //TODO("Not yet implemented")
    }

    class MovingAverage(size: Int) {
        //Moving average class
        private val circularBuffer: DoubleArray = DoubleArray(size)
        var value = 0.0
            private set
        private var circularIndex = 0
        private var count = 0
        fun pushValue(x: Double) {
            if (count++ == 0) {
                primeBuffer(x)
            }
            val lastValue = circularBuffer[circularIndex]
            value += (x - lastValue) / circularBuffer.size
            circularBuffer[circularIndex] = x
            circularIndex = nextIndex(circularIndex)
        }

        fun reset() {
            count = 0
            circularIndex = 0
            value = 0.0
        }

        fun getCount(): Long {
            return count.toLong()
        }

        private fun primeBuffer(`val`: Double) {
            for (i in circularBuffer.indices) {
                circularBuffer[i] = `val`
            }
            value = `val`
        }

        private fun nextIndex(curIndex: Int): Int {
            return if (curIndex + 1 >= circularBuffer.size) {
                0
            } else curIndex + 1
        }

        init {
            reset()
        }
    }

    fun makeZero(number:Double): Double {
        if(number < 0) {
            return 0.0;
        }
        else {
            return number;
        }

    }

    class RunningAverage() {
        private var total = 0.0
        private var count = 0
        var value = 0.0
        var max = 0.0
        init {
            reset()
        }
        fun reset() {
            total = 0.0
            count = 0
            value = 0.0
        }

        fun addValue(newValue:Double) {
            total += newValue
            count++
            value = total/count
            if(max < newValue) {
                max = newValue
            }
        }
    }
}