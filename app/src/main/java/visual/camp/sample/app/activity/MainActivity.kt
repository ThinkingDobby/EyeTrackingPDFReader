package visual.camp.sample.app.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import camp.visual.gazetracker.GazeTracker
import camp.visual.gazetracker.callback.*
import camp.visual.gazetracker.constant.*
import camp.visual.gazetracker.filter.OneEuroFilterManager
import camp.visual.gazetracker.gaze.GazeInfo
import camp.visual.gazetracker.state.ScreenState
import camp.visual.gazetracker.state.TrackingState
import camp.visual.gazetracker.util.ViewLayoutChecker
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import visual.camp.sample.app.GazeTrackerManager
import visual.camp.sample.app.GazeTrackerManager.LoadCalibrationResult
import visual.camp.sample.app.R
import visual.camp.sample.view.*


class MainActivity : AppCompatActivity() {
    private var gazeTrackerManager: GazeTrackerManager? = null
    private val viewLayoutChecker = ViewLayoutChecker()
    private val backgroundThread = HandlerThread("background")
    private var backgroundHandler: Handler? = null

    private var pdfFileUri: Uri? = null
    private var pdfView: PDFView? = null

    private var firstBlink = 0L
    private var posX = 0F
    private var posY = 0F
    private var firstX = 0F
    private var firstY = 0f
    private var check = false
    private var viewType = "pdf"
    private var autoScrollThread: Job? = null

    private var autoScroll = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gazeTrackerManager = GazeTrackerManager.makeNewInstance(this)
        Log.i(TAG, "gazeTracker version: " + GazeTracker.getVersionName())

        initView()
        checkPermission()
        initHandler()

        GlobalScope.launch {
            loading()
            runOnUiThread {
                btnLoad!!.callOnClick()
            }
        }

        GlobalScope.launch {
            initGaze()
            startTracking()
        }
    }

    override fun onStart() {
        super.onStart()
        if (preview!!.isAvailable) {
            // When if textureView available
            gazeTrackerManager!!.setCameraPreview(preview!!)
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
        gazeTrackerManager!!.removeCameraPreview(preview!!)
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
    private var viewCalibration: CalibrationViewer? = null
    private var viewEyeBlink: EyeBlinkView? = null
    private var viewAttention: AttentionView? = null
    private var viewDrowsiness: DrowsinessView? = null
    private var loadingScreen: ConstraintLayout? = null
    private var settingScreen: ConstraintLayout? = null
    private var fileLoadScreen: ConstraintLayout? = null
    private var btnLoad: ConstraintLayout? = null
    private var btnSetting: ConstraintLayout? = null
    private var btnBack: ConstraintLayout? = null
    private var btnCalibration: ConstraintLayout? = null
    private var tvAutoScroll: TextView? = null
    private var tvExpand: TextView? = null
    private var btnExpand: ConstraintLayout? = null
    private var btnAutoScroll: ConstraintLayout? = null
    private var firstFile: ConstraintLayout? = null
    private var secondFile: ConstraintLayout? = null

    // gaze coord filter
    private var swUseGazeFilter: SwitchCompat? = null
    private var swStatusBlink: SwitchCompat? = null
    private var swStatusAttention: SwitchCompat? = null
    private var swStatusDrowsiness: SwitchCompat? = null
    private var isUseGazeFilter = true
    private var isStatusBlink = true
    private var isStatusAttention = false
    private var isStatusDrowsiness = false
    private var activeStatusCount = 0

    // calibration type
    private var rgCalibration: RadioGroup? = null
    private var rgAccuracy: RadioGroup? = null
    private var calibrationType = CalibrationModeType.DEFAULT
    private var criteria = AccuracyCriteria.DEFAULT

    private fun initView() {
        layoutProgress = findViewById(R.id.layout_progress)
        layoutProgress?.setOnClickListener(null)
        viewWarningTracking = findViewById(R.id.view_warning_tracking)
        preview = findViewById(R.id.preview)
        preview?.setSurfaceTextureListener(surfaceTextureListener)
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
        pdfView = findViewById(R.id.main_pdfview)
        loadingScreen = findViewById(R.id.main_cl_loading)
        settingScreen = findViewById(R.id.main_cl_setting)
        btnLoad = findViewById(R.id.main_btn_load)
        btnSetting = findViewById(R.id.main_btn_setting)
        btnBack = findViewById(R.id.main_btn_back)
        btnCalibration = findViewById(R.id.main_btn_calibration)
        tvAutoScroll = findViewById(R.id.main_tv_auto_scroll)
        tvExpand = findViewById(R.id.main_tv_expand)
        btnAutoScroll = findViewById(R.id.main_btn_auto_scroll)
        btnExpand = findViewById(R.id.main_btn_expand)
        fileLoadScreen = findViewById(R.id.main_cl_file_load)
        firstFile = findViewById(R.id.main_cl_file_first)
        secondFile = findViewById(R.id.main_cl_file_second)

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

        btnLoad!!.setOnClickListener {
            viewType = "load"
            fileLoadScreen!!.visibility = View.VISIBLE
            settingScreen!!.visibility = View.INVISIBLE
        }
        btnSetting!!.setOnClickListener {
            viewType = "setting"
            settingScreen!!.visibility = View.VISIBLE
            runBlocking {
                if(autoScrollThread !=null) autoScrollThread!!.cancel()
            }
        }
        btnBack!!.setOnClickListener {
            viewType = "pdf"
            settingScreen!!.visibility = View.INVISIBLE

            autoScroll = tvAutoScroll!!.text == "ON"
            if (autoScroll) {
                var nowPage = 0
                autoScrollThread = GlobalScope.launch() {
                    while (autoScroll) {
                        delay(1000)
                        runOnUiThread {
                            pdfView!!.jumpTo(nowPage++)
                        }
                    }
                }
            }
        }
        btnAutoScroll!!.setOnClickListener {
            if (tvAutoScroll!!.text == "ON") {
                tvAutoScroll!!.text = "OFF"
            } else {
                tvAutoScroll!!.text = "ON"
            }
        }
        btnCalibration!!.setOnClickListener {
            Log.i("eye", "calibration")

            viewType = "cali"
            startCalibration()
        }
        btnExpand!!.setOnClickListener{
            if (pdfView!!.zoom < pdfView!!.midZoom) {
                pdfView!!.zoomWithAnimation(0F, 0F, pdfView!!.midZoom);
            } else if (pdfView!!.zoom < pdfView!!.maxZoom) {
                pdfView!!.zoomWithAnimation(0F, 0F, pdfView!!.maxZoom);
            } else {
                pdfView!!.resetZoomWithAnimation();
            }
        }

        firstFile!!.setOnClickListener {
            viewType = "pdf"
            fileLoadScreen!!.visibility = View.INVISIBLE

        }
        secondFile!!.setOnClickListener {
            viewType = "pdf"
            fileLoadScreen!!.visibility = View.INVISIBLE

        }
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
            gazeTrackerManager!!.setCameraPreview(preview!!)
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
            viewPoint!!.hideLine()
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
    private val isCalibrating: Boolean
        private get() = gazeTrackerManager!!.isCalibrating
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

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var maxY = Float.MIN_VALUE

    private val gazeCallback = GazeCallback { gazeInfo ->
        processOnGaze(gazeInfo)
        posX = gazeInfo.x
        posY = gazeInfo.y
//        Log.i("checkCoordinate", "${gazeInfo.x},${gazeInfo.y}")

        if (!posX.isNaN() && !posY.isNaN()) {
            minX = minOf(minX, posX)
            maxX = maxOf(maxX, posX)
            minY = minOf(minY, posY)
            maxY = maxOf(maxY, posY)
        }
    }
    private val userStatusCallback: UserStatusCallback = object : UserStatusCallback {
        override fun onAttention(timestampBegin: Long, timestampEnd: Long, attentionScore: Float) {
            viewAttention!!.setAttention(attentionScore)
        }

        override fun onBlink(
            timestamp: Long,
            isBlinkLeft: Boolean,
            isBlinkRight: Boolean,
            isBlink: Boolean,
            eyeOpenness: Float
        ) {

            Log.i("focus", currentFocus.toString())
            if (!isCalibrating && viewType == "cali") {
                GlobalScope.launch {
                    delay(1000)
                    viewType = "setting"
                }
            }

            viewEyeBlink!!.setLeftEyeBlink(isBlinkLeft)
            viewEyeBlink!!.setRightEyeBlink(isBlinkRight)
            viewEyeBlink!!.setEyeBlink(isBlink)

            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val optionHeight = dpToPx(dp = 90F)


            val nowPage = pdfView!!.currentPage

            Log.i("viewType", "$viewType")
            if (isBlink && !check) {// 첫번째 깜박임
                firstBlink = System.nanoTime()
                firstX = posX
                firstY = posY
                check = true
            } else if (isBlink && check) {// 두번째 깜박임
                val secondBlink = System.nanoTime()
                val timeDifference = secondBlink - firstBlink// 깜박임 시간 차 계산
                Log.i("difference", "difference: $timeDifference")

                if (timeDifference < 200000) { // 동시에 두번 깜박임 감지
                    if (viewType == "pdf") { // pdf 화면
                        Log.i("viewType", "pdf")
                        runOnUiThread {
                            if (firstY < ((screenHeight - optionHeight) / 2)) pdfView!!.jumpTo(
                                nowPage - 1
                            ) // 이전 페이지 이동
                            else if (firstY < (screenHeight - optionHeight)) pdfView!!.jumpTo(
                                nowPage + 1
                            ) // 다음 페이지 이동
                            else if (firstX < (screenWidth / 2)) { // pdf 문서 불러오기
                                btnLoad!!.callOnClick()
                            } else {  // 환경설정
                                btnSetting!!.callOnClick()
                            }
                        }
                    } else if (viewType == "setting") { // 세팅 화면
                        Log.i("viewType", "setting")
                        if (firstY > dpToPx(85F) && firstY < dpToPx(200F)) { // 초점 맟추기
                            btnCalibration!!.callOnClick()
                        } else if (firstY > (screenHeight - dpToPx(237F)) && firstY < (screenHeight - optionHeight)) { // 뒤로가기
                            btnBack!!.callOnClick()
                        } else if (firstY > (screenHeight - optionHeight) && firstX < (screenWidth / 2)) { // pdf 문서 불러오기
                            btnLoad!!.callOnClick()
                        }
                    } else if (viewType == "load"){
                    }
                    check = false
                } else {
                    firstBlink = System.nanoTime()
                    firstX = posX
                    firstY = posY
                    check = true
                }
            }
        }


        override fun onDrowsiness(timestamp: Long, isDrowsiness: Boolean) {
            Log.i(TAG, "check User Status Drowsiness $isDrowsiness")
            viewDrowsiness!!.setDrowsiness(isDrowsiness)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
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

    private suspend fun initGaze() {
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
        delay(2500)
    }

    private suspend fun loading() {
        delay(2500)
        loadingScreen!!.visibility = View.INVISIBLE
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

    private fun getFileFromStorage() {
        val intent = Intent()
        intent.type = "application/pdf"
        intent.action = Intent.ACTION_OPEN_DOCUMENT

        startActivityForResult(Intent.createChooser(intent, "get file"), 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0 && resultCode == RESULT_OK) {
            if (data != null) {
                pdfFileUri = data.data
                displayPDFFromUri(pdfFileUri!!, 0)
                Log.d("uri", pdfFileUri.toString())
            }
        }
    }

    private fun displayPDFFromUri(uri: Uri, pageNumber: Int) {
        pdfView!!.fromUri(uri)
            .defaultPage(pageNumber)
//            .onPageChange(this)
            .enableAnnotationRendering(true)
//            .onLoad(this)
            .scrollHandle(DefaultScrollHandle(this))
            .spacing(10) // in dp
//            .onPageError(this)
            .load()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA, // 시선 추적 input
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        private const val REQ_PERMISSION = 1000
    }
}