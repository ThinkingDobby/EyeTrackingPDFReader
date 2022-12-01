package visual.camp.sample.app.activity

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import visual.camp.sample.app.GazeTrackerManager
import camp.visual.gazetracker.util.ViewLayoutChecker
import android.os.HandlerThread
import android.os.Bundle
import visual.camp.sample.app.R
import visual.camp.sample.app.activity.MainActivity
import camp.visual.gazetracker.GazeTracker
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.view.TextureView
import visual.camp.sample.view.PointView
import visual.camp.sample.view.CalibrationViewer
import visual.camp.sample.view.EyeBlinkView
import visual.camp.sample.view.AttentionView
import visual.camp.sample.view.DrowsinessView
import androidx.appcompat.widget.SwitchCompat
import camp.visual.gazetracker.constant.CalibrationModeType
import camp.visual.gazetracker.constant.AccuracyCriteria
import androidx.appcompat.widget.AppCompatTextView
import android.view.TextureView.SurfaceTextureListener
import android.graphics.SurfaceTexture
import camp.visual.gazetracker.util.ViewLayoutChecker.ViewLayoutListener
import camp.visual.gazetracker.state.ScreenState
import camp.visual.gazetracker.callback.InitializationCallback
import camp.visual.gazetracker.constant.InitializationErrorType
import camp.visual.gazetracker.filter.OneEuroFilterManager
import camp.visual.gazetracker.callback.GazeCallback
import camp.visual.gazetracker.gaze.GazeInfo
import camp.visual.gazetracker.callback.UserStatusCallback
import camp.visual.gazetracker.state.TrackingState
import camp.visual.gazetracker.callback.CalibrationCallback
import camp.visual.gazetracker.callback.StatusCallback
import camp.visual.gazetracker.constant.StatusErrorType
import camp.visual.gazetracker.constant.UserStatusOption
import visual.camp.sample.app.GazeTrackerManager.LoadCalibrationResult
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import visual.camp.sample.app.activity.DemoActivity


class MainActivity : AppCompatActivity() {
    private var gazeTrackerManager: GazeTrackerManager? = null
    private val viewLayoutChecker = ViewLayoutChecker()
    private val backgroundThread = HandlerThread("background")
    private var backgroundHandler: Handler? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gazeTrackerManager = GazeTrackerManager.makeNewInstance(this)
        Log.i(TAG, "gazeTracker version: " + GazeTracker.getVersionName())
        initView()
        checkPermission()
        initHandler()
    }

    override fun onStart() {
        super.onStart()
        if (preview!!.isAvailable) {
            // When if textureView available
            gazeTrackerManager!!.setCameraPreview(preview)
        }
        gazeTrackerManager!!.setGazeTrackerCallbacks(
            gazeCallback,
            calibrationCallback,
            statusCallback,
            userStatusCallback
        )
        Log.i(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        // 화면 전환후에도 체크하기 위해
        setOffsetOfView()
        gazeTrackerManager!!.startGazeTracking()
    }

    override fun onPause() {
        super.onPause()
        gazeTrackerManager!!.stopGazeTracking()
        Log.i(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
        gazeTrackerManager!!.removeCameraPreview(preview)
        gazeTrackerManager!!.removeCallbacks(
            gazeCallback,
            calibrationCallback,
            statusCallback,
            userStatusCallback
        )
        Log.i(TAG, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseHandler()
        viewLayoutChecker.releaseChecker()
    }

    // handler
    private fun initHandler() {
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun releaseHandler() {
        backgroundThread.quitSafely()
    }

    // handler end
    // permission
    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check permission status
            if (!hasPermissions(PERMISSIONS)) {
                requestPermissions(PERMISSIONS, REQ_PERMISSION)
            } else {
                checkPermission(true)
            }
        } else {
            checkPermission(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun hasPermissions(permissions: Array<String>): Boolean {
        var result: Int
        // Check permission status in string array
        for (perms in permissions) {
            if (perms == Manifest.permission.SYSTEM_ALERT_WINDOW) {
                if (!Settings.canDrawOverlays(this)) {
                    return false
                }
            }
            result = ContextCompat.checkSelfPermission(this, perms)
            if (result == PackageManager.PERMISSION_DENIED) {
                // When if unauthorized permission found
                return false
            }
        }
        // When if all permission allowed
        return true
    }

    private fun checkPermission(isGranted: Boolean) {
        if (isGranted) {
            permissionGranted()
        } else {
            showToast("not granted permissions", true)
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMISSION -> if (grantResults.size > 0) {
                val cameraPermissionAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (cameraPermissionAccepted) {
                    checkPermission(true)
                } else {
                    checkPermission(false)
                }
            }
        }
    }

    private fun permissionGranted() {
        setViewAtGazeTrackerState()
    }

    // permission end
    // view
    private var preview: TextureView? = null
    private var layoutProgress: View? = null
    private var viewWarningTracking: View? = null
    private var viewPoint: PointView? = null
    private var btnInitGaze: Button? = null
    private var btnReleaseGaze: Button? = null
    private var btnStartTracking: Button? = null
    private var btnStopTracking: Button? = null
    private var btnStartCalibration: Button? = null
    private var btnStopCalibration: Button? = null
    private var btnSetCalibration: Button? = null
    private var btnGuiDemo: Button? = null
    private var viewCalibration: CalibrationViewer? = null
    private var viewEyeBlink: EyeBlinkView? = null
    private var viewAttention: AttentionView? = null
    private var viewDrowsiness: DrowsinessView? = null
    // 위치 좌표 출력
    public var edit_T: TextView? = null

    // gaze coord filter
    private var swUseGazeFilter: SwitchCompat? = null
    private var swStatusBlink: SwitchCompat? = null
    private var swStatusAttention: SwitchCompat? = null
    private var swStatusDrowsiness: SwitchCompat? = null
    private var isUseGazeFilter = true
    private var isStatusBlink = false
    private var isStatusAttention = false
    private var isStatusDrowsiness = false
    private var activeStatusCount = 0

    // calibration type
    private var rgCalibration: RadioGroup? = null
    private var rgAccuracy: RadioGroup? = null
    private var calibrationType = CalibrationModeType.DEFAULT
    private var criteria = AccuracyCriteria.DEFAULT

    private fun initView() {
        edit_T = findViewById(R.id.edit_text)

        layoutProgress = findViewById(R.id.layout_progress)
        layoutProgress?.setOnClickListener(null)
        viewWarningTracking = findViewById(R.id.view_warning_tracking)
        preview = findViewById(R.id.preview)
        preview?.setSurfaceTextureListener(surfaceTextureListener)
        btnInitGaze = findViewById(R.id.btn_init_gaze)
        btnReleaseGaze = findViewById(R.id.btn_release_gaze)
        btnInitGaze?.setOnClickListener(onClickListener)
        btnReleaseGaze?.setOnClickListener(onClickListener)
        btnStartTracking = findViewById(R.id.btn_start_tracking)
        btnStopTracking = findViewById(R.id.btn_stop_tracking)
        btnStartTracking?.setOnClickListener(onClickListener)
        btnStopTracking?.setOnClickListener(onClickListener)
        btnStartCalibration = findViewById(R.id.btn_start_calibration)
        btnStopCalibration = findViewById(R.id.btn_stop_calibration)
        btnStartCalibration?.setOnClickListener(onClickListener)
        btnStopCalibration?.setOnClickListener(onClickListener)
        btnSetCalibration = findViewById(R.id.btn_set_calibration)
        btnSetCalibration?.setOnClickListener(onClickListener)
        btnGuiDemo = findViewById(R.id.btn_gui_demo)
        btnGuiDemo?.setOnClickListener(onClickListener)
        viewPoint = findViewById(R.id.view_point)
        viewCalibration = findViewById(R.id.view_calibration)
        swUseGazeFilter = findViewById(R.id.sw_use_gaze_filter)
        rgCalibration = findViewById(R.id.rg_calibration)
        rgAccuracy = findViewById(R.id.rg_accuracy)
        viewEyeBlink = findViewById(R.id.view_eye_blink)
        viewAttention = findViewById(R.id.view_attention)
        viewDrowsiness = findViewById(R.id.view_drowsiness)
        swStatusBlink = findViewById(R.id.sw_status_blink)
        swStatusAttention = findViewById(R.id.sw_status_attention)
        swStatusDrowsiness = findViewById(R.id.sw_status_drowsiness)
        swUseGazeFilter?.setChecked(isUseGazeFilter)
        swStatusBlink?.setChecked(isStatusBlink)
        swStatusAttention?.setChecked(isStatusAttention)
        swStatusDrowsiness?.setChecked(isStatusDrowsiness)
        val rbCalibrationOne = findViewById<RadioButton>(R.id.rb_calibration_one)
        val rbCalibrationFive = findViewById<RadioButton>(R.id.rb_calibration_five)
        val rbCalibrationSix = findViewById<RadioButton>(R.id.rb_calibration_six)
        when (calibrationType) {
            CalibrationModeType.ONE_POINT -> rbCalibrationOne.isChecked = true
            CalibrationModeType.SIX_POINT -> rbCalibrationSix.isChecked = true
            else ->                 // default = five point
                rbCalibrationFive.isChecked = true
        }
        swUseGazeFilter?.setOnCheckedChangeListener(onCheckedChangeSwitch)
        swStatusBlink?.setOnCheckedChangeListener(onCheckedChangeSwitch)
        swStatusAttention?.setOnCheckedChangeListener(onCheckedChangeSwitch)
        swStatusDrowsiness?.setOnCheckedChangeListener(onCheckedChangeSwitch)
        rgCalibration?.setOnCheckedChangeListener(onCheckedChangeRadioButton)
        rgAccuracy?.setOnCheckedChangeListener(onCheckedChangeRadioButton)
        viewEyeBlink?.setVisibility(View.GONE)
        viewAttention?.setVisibility(View.GONE)
        viewDrowsiness?.setVisibility(View.GONE)
        hideProgress()
        setOffsetOfView()
        setViewAtGazeTrackerState()
    }

    private val onCheckedChangeRadioButton =
        RadioGroup.OnCheckedChangeListener { group, checkedId ->
            if (group === rgCalibration) {
                if (checkedId == R.id.rb_calibration_one) {
                    calibrationType = CalibrationModeType.ONE_POINT
                } else if (checkedId == R.id.rb_calibration_five) {
                    calibrationType = CalibrationModeType.FIVE_POINT
                } else if (checkedId == R.id.rb_calibration_six) {
                    calibrationType = CalibrationModeType.SIX_POINT
                }
            } else if (group === rgAccuracy) {
                if (checkedId == R.id.rb_accuracy_default) {
                    criteria = AccuracyCriteria.DEFAULT
                } else if (checkedId == R.id.rb_accuracy_low) {
                    criteria = AccuracyCriteria.LOW
                } else if (checkedId == R.id.rb_accuracy_high) {
                    criteria = AccuracyCriteria.HIGH
                }
            }
        }
    private val onCheckedChangeSwitch =
        CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView === swUseGazeFilter) {
                isUseGazeFilter = isChecked
            } else if (buttonView === swStatusBlink) {
                isStatusBlink = isChecked
                if (isStatusBlink) {
                    viewEyeBlink!!.visibility = View.VISIBLE
                    activeStatusCount++
                } else {
                    viewEyeBlink!!.visibility = View.GONE
                    activeStatusCount--
                }
            } else if (buttonView === swStatusAttention) {
                isStatusAttention = isChecked
                if (isStatusAttention) {
                    viewAttention!!.visibility = View.VISIBLE
                    activeStatusCount++
                } else {
                    viewAttention!!.visibility = View.GONE
                    activeStatusCount--
                }
            } else if (buttonView === swStatusDrowsiness) {
                isStatusDrowsiness = isChecked
                if (isStatusDrowsiness) {
                    viewDrowsiness!!.visibility = View.VISIBLE
                    activeStatusCount++
                } else {
                    viewDrowsiness!!.visibility = View.GONE
                    activeStatusCount--
                }
            }
        }
    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            // When if textureView available
            gazeTrackerManager!!.setCameraPreview(preview)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    // The gaze or calibration coordinates are delivered only to the absolute coordinates of the entire screen.
    // The coordinate system of the Android view is a relative coordinate system,
    // so the offset of the view to show the coordinates must be obtained and corrected to properly show the information on the screen.
    private fun setOffsetOfView() {
        viewLayoutChecker.setOverlayView(viewPoint!!) { x, y ->
            viewPoint!!.setOffset(x, y)
            viewCalibration!!.setOffset(x, y)
        }
    }

    private fun showProgress() {
        if (layoutProgress != null) {
            runOnUiThread { layoutProgress!!.visibility = View.VISIBLE }
        }
    }

    private fun hideProgress() {
        if (layoutProgress != null) {
            runOnUiThread { layoutProgress!!.visibility = View.INVISIBLE }
        }
    }

    private fun showTrackingWarning() {
        runOnUiThread { viewWarningTracking!!.visibility = View.VISIBLE }
    }

    private fun hideTrackingWarning() {
        runOnUiThread { viewWarningTracking!!.visibility = View.INVISIBLE }
    }

    private val onClickListener = View.OnClickListener { v ->
        if (v === btnInitGaze) {
            initGaze()
        } else if (v === btnReleaseGaze) {
            releaseGaze()
        } else if (v === btnStartTracking) {
            startTracking()
        } else if (v === btnStopTracking) {
            stopTracking()
        } else if (v === btnStartCalibration) {
            startCalibration()
        } else if (v === btnStopCalibration) {
            stopCalibration()
        } else if (v === btnSetCalibration) {
            setCalibration()
        } else if (v === btnGuiDemo) {
            showGuiDemo()
        }
    }

    private fun showToast(msg: String, isShort: Boolean) {
        runOnUiThread {
            Toast.makeText(
                this@MainActivity,
                msg,
                if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }

    // 시선 위치 표시
    private fun showGazePoint(x: Float, y: Float, type: ScreenState) {
        runOnUiThread {
            viewPoint!!.setType(if (type == ScreenState.INSIDE_OF_SCREEN) PointView.TYPE_DEFAULT else PointView.TYPE_OUT_OF_SCREEN)
            viewPoint!!.setPosition(x, y)
        }
    }

    private fun setCalibrationPoint(x: Float, y: Float) {
        runOnUiThread {
            viewCalibration!!.visibility = View.VISIBLE
            viewCalibration!!.changeDraw(true, null)
            viewCalibration!!.setPointPosition(x, y)
            viewCalibration!!.setPointAnimationPower(0f)
        }
    }

    private fun setCalibrationProgress(progress: Float) {
        runOnUiThread { viewCalibration!!.setPointAnimationPower(progress) }
    }

    private fun hideCalibrationView() {
        runOnUiThread { viewCalibration!!.visibility = View.INVISIBLE }
    }

    private fun setViewAtGazeTrackerState() {
        Log.i(TAG, "gaze : $isTrackerValid, tracking $isTracking")
        runOnUiThread {
            btnInitGaze!!.isEnabled = !isTrackerValid
            btnReleaseGaze!!.isEnabled = isTrackerValid
            btnStartTracking!!.isEnabled = isTrackerValid && !isTracking
            btnStopTracking!!.isEnabled = isTracking
            btnStartCalibration!!.isEnabled = isTracking
            btnStopCalibration!!.isEnabled = isTracking
            btnSetCalibration!!.isEnabled = isTrackerValid
            if (!isTracking) {
                hideCalibrationView()
            }
        }
    }

    private fun setStatusSwitchState(isEnabled: Boolean) {
        runOnUiThread {
            if (!isEnabled) {
                swStatusBlink!!.isEnabled = false
                swStatusAttention!!.isEnabled = false
                swStatusDrowsiness!!.isEnabled = false
            } else {
                swStatusBlink!!.isEnabled = true
                swStatusAttention!!.isEnabled = true
                swStatusDrowsiness!!.isEnabled = true
            }
        }
    }

    // view end
    // gazeTracker
    private val isTrackerValid: Boolean
        private get() = gazeTrackerManager!!.hasGazeTracker()
    private val isTracking: Boolean
        private get() = gazeTrackerManager!!.isTracking
    private val initializationCallback = InitializationCallback { gazeTracker, error ->
        if (gazeTracker != null) {
            initSuccess(gazeTracker)
        } else {
            initFail(error)
        }
    }

    private fun initSuccess(gazeTracker: GazeTracker) {
        setViewAtGazeTrackerState()
        hideProgress()
    }

    private fun initFail(error: InitializationErrorType) {
        hideProgress()
    }

    private val oneEuroFilterManager = OneEuroFilterManager(2)

    // 시선 좌표
    var position_x: Float = 0F
    var position_y: Float = 0F
    // 두번 깜박임 카운트
    var count = 0
    // 두번 깜박임 시간 측정
    var first_blink = System.currentTimeMillis()

    private val gazeCallback = GazeCallback { gazeInfo ->
        processOnGaze(gazeInfo)
        Log.i(TAG, "check eyeMovement " + gazeInfo.eyeMovementState)

        // 응시하는 좌표 출력
        var co_x = "${gazeInfo.x.toString()}  ,  ${gazeInfo.y.toString()}"
        position_x = gazeInfo.x
        position_y = gazeInfo.y
        edit_T?.text= co_x


        Log.i("checkCoordinate", "${gazeInfo.x},${gazeInfo.y}")
    }
    private val userStatusCallback: UserStatusCallback = object : UserStatusCallback {
        override fun onAttention(timestampBegin: Long, timestampEnd: Long, attentionScore: Float) {
            Log.i(TAG, "check User Status Attention Rate $attentionScore")
            viewAttention!!.setAttention(attentionScore)
        }

        // Blink 시 동작 지정
        override fun onBlink(
            timestamp: Long,
            isBlinkLeft: Boolean,
            isBlinkRight: Boolean,
            isBlink: Boolean,
            eyeOpenness: Float
        ) {
            Log.i(
                TAG,
                "check User Status Blink Left: $isBlinkLeft, Right: $isBlinkRight, Blink: $isBlink, eyeOpenness: $eyeOpenness"
            )
            viewEyeBlink!!.setLeftEyeBlink(isBlinkLeft)
            viewEyeBlink!!.setRightEyeBlink(isBlinkRight)
            viewEyeBlink!!.setEyeBlink(isBlink)


            // 2번 blink 시 시선 추적 중지

            // 첫번째 깜박임
            if(isBlink && count==0){
                if(position_y < 1000){// 시선 위치 확인(화면 상단을 봐야지만 실행)
                    Log.i("count","$count" )
                    first_blink = System.currentTimeMillis()// 첫번째 깜박인 시간
                    count=1
                }

            }else if(isBlink && count==1){// 두번째 깜박임
                if(position_y < 1000){

                    Log.i("count","$count" )
                    var second_blink = System.currentTimeMillis()// 두번째 깜박인 시간
                    var time_difference = second_blink-first_blink
                    Log.i("time_differnece","$time_difference" )
                    if(time_difference <3000){// 깜박임 시간 차에 따라 행동
                        stopTracking()
                    }
                    count = 0
                }
            }
        }

        override fun onDrowsiness(timestamp: Long, isDrowsiness: Boolean) {
            Log.i(TAG, "check User Status Drowsiness $isDrowsiness")
            viewDrowsiness!!.setDrowsiness(isDrowsiness)
        }
    }

    // 시선 위치 좌표 표현
    private fun processOnGaze(gazeInfo: GazeInfo) {
        if (gazeInfo.trackingState == TrackingState.SUCCESS) {
            hideTrackingWarning()
            if (!gazeTrackerManager!!.isCalibrating) {
                val filtered_gaze = filterGaze(gazeInfo)
                showGazePoint(filtered_gaze[0], filtered_gaze[1], gazeInfo.screenState)

            }
        } else {
            showTrackingWarning()
        }
    }

    private fun filterGaze(gazeInfo: GazeInfo): FloatArray {
        if (isUseGazeFilter) {
            if (oneEuroFilterManager.filterValues(gazeInfo.timestamp, gazeInfo.x, gazeInfo.y)) {
                return oneEuroFilterManager.filteredValues
            }
        }
        return floatArrayOf(gazeInfo.x, gazeInfo.y)
    }

    private val calibrationCallback: CalibrationCallback = object : CalibrationCallback {
        override fun onCalibrationProgress(progress: Float) {
            setCalibrationProgress(progress)
        }

        override fun onCalibrationNextPoint(x: Float, y: Float) {
            setCalibrationPoint(x, y)
            // Give time to eyes find calibration coordinates, then collect data samples
            backgroundHandler!!.postDelayed({ startCollectSamples() }, 1000)
        }

        override fun onCalibrationFinished(calibrationData: DoubleArray) {
            // When calibration is finished, calibration data is stored to SharedPreference
            hideCalibrationView()
            showToast("calibrationFinished", true)
        }
    }
    private val statusCallback: StatusCallback = object : StatusCallback {
        override fun onStarted() {
            // isTracking true
            // When if camera stream starting
            setViewAtGazeTrackerState()
        }

        override fun onStopped(error: StatusErrorType) {
            // isTracking false
            // When if camera stream stopping
            setViewAtGazeTrackerState()
            if (error != StatusErrorType.ERROR_NONE) {
                when (error) {
                    StatusErrorType.ERROR_CAMERA_START ->                         // When if camera stream can't start
                        showToast("ERROR_CAMERA_START ", false)
                    StatusErrorType.ERROR_CAMERA_INTERRUPT ->                         // When if camera stream interrupted
                        showToast("ERROR_CAMERA_INTERRUPT ", false)
                }
            }
        }
    }

    private fun initGaze() {
        showProgress()
        val userStatusOption = UserStatusOption()
        if (isStatusAttention) {
            userStatusOption.useAttention()
        }
        if (isStatusBlink) {
            userStatusOption.useBlink()
        }
        if (isStatusDrowsiness) {
            userStatusOption.useDrowsiness()
        }
        Log.i(
            TAG,
            "init option attention $isStatusAttention, blink $isStatusBlink, drowsiness $isStatusDrowsiness"
        )
        gazeTrackerManager!!.initGazeTracker(initializationCallback, userStatusOption)
        setStatusSwitchState(false)
    }

    private fun releaseGaze() {
        gazeTrackerManager!!.deinitGazeTracker()
        setStatusSwitchState(true)
        setViewAtGazeTrackerState()
    }

    private fun startTracking() {
        gazeTrackerManager!!.startGazeTracking()
    }

    private fun stopTracking() {
        gazeTrackerManager!!.stopGazeTracking()
    }

    private fun startCalibration(): Boolean {
        val isSuccess = gazeTrackerManager!!.startCalibration(calibrationType, criteria)
        if (!isSuccess) {
            showToast("calibration start fail", false)
        }
        setViewAtGazeTrackerState()
        return isSuccess
    }

    // Collect the data samples used for calibration
    private fun startCollectSamples(): Boolean {
        val isSuccess = gazeTrackerManager!!.startCollectingCalibrationSamples()
        setViewAtGazeTrackerState()
        return isSuccess
    }

    private fun stopCalibration() {
        gazeTrackerManager!!.stopCalibration()
        hideCalibrationView()
        setViewAtGazeTrackerState()
    }

    private fun setCalibration() {
        val result = gazeTrackerManager!!.loadCalibrationData()
        when (result) {
            LoadCalibrationResult.SUCCESS -> showToast("setCalibrationData success", false)
            LoadCalibrationResult.FAIL_DOING_CALIBRATION -> showToast("calibrating", false)
            LoadCalibrationResult.FAIL_NO_CALIBRATION_DATA -> showToast(
                "Calibration data is null",
                true
            )
            LoadCalibrationResult.FAIL_HAS_NO_TRACKER -> showToast(
                "No tracker has initialized",
                true
            )
        }
        setViewAtGazeTrackerState()
    }

    private fun showGuiDemo() {
        val intent = Intent(applicationContext, DemoActivity::class.java)
        startActivity(intent)
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA // 시선 추적 input
        )
        private const val REQ_PERMISSION = 1000
    }
}