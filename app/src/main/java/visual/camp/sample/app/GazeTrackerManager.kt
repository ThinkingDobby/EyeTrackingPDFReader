package visual.camp.sample.app

import android.content.Context
import camp.visual.gazetracker.callback.InitializationCallback
import camp.visual.gazetracker.callback.GazeCallback
import camp.visual.gazetracker.callback.CalibrationCallback
import camp.visual.gazetracker.callback.StatusCallback
import camp.visual.gazetracker.callback.ImageCallback
import camp.visual.gazetracker.callback.UserStatusCallback
import android.view.TextureView
import camp.visual.gazetracker.GazeTracker
import camp.visual.gazetracker.constant.UserStatusOption
import camp.visual.gazetracker.callback.GazeTrackerCallback
import camp.visual.gazetracker.constant.CalibrationModeType
import camp.visual.gazetracker.constant.AccuracyCriteria
import visual.camp.sample.app.GazeTrackerManager.LoadCalibrationResult
import visual.camp.sample.app.calibration.CalibrationDataStorage
import camp.visual.gazetracker.constant.InitializationErrorType
import camp.visual.gazetracker.gaze.GazeInfo
import camp.visual.gazetracker.constant.StatusErrorType
import visual.camp.sample.app.GazeTrackerManager
import java.lang.ref.WeakReference
import java.util.ArrayList

class GazeTrackerManager private constructor(context: Context) {
    private val initializationCallbacks: MutableList<InitializationCallback> = ArrayList()
    private val gazeCallbacks: MutableList<GazeCallback> = ArrayList()
    private val calibrationCallbacks: MutableList<CalibrationCallback> = ArrayList()
    private val statusCallbacks: MutableList<StatusCallback> = ArrayList()
    private val imageCallbacks: MutableList<ImageCallback> = ArrayList()
    private val userStatusCallbacks: MutableList<UserStatusCallback> = ArrayList()
    private var cameraPreview: WeakReference<TextureView>? = null
    private val mContext: WeakReference<Context> = WeakReference(context)
    var localGazeTracker: GazeTracker? = null

    // TODO: change licence key
    var SEESO_LICENSE_KEY = "dev_azwbwgom3btel6w2tbruwgcq8glz2hepulbdm0bv"
    fun hasGazeTracker(): Boolean {
        return localGazeTracker != null
    }

    fun initGazeTracker(callback: InitializationCallback, option: UserStatusOption?) {
        initializationCallbacks.add(callback)
        GazeTracker.initGazeTracker(
            mContext.get(),
            SEESO_LICENSE_KEY,
            initializationCallback,
            option
        )
    }

    fun deinitGazeTracker() {
        if (hasGazeTracker()) {
            GazeTracker.deinitGazeTracker(localGazeTracker)
            localGazeTracker = null
        }
    }

    fun setGazeTrackerCallbacks(vararg callbacks: GazeTrackerCallback?) {
        for (callback in callbacks) {
            if (callback is GazeCallback) {
                gazeCallbacks.add(callback)
            } else if (callback is CalibrationCallback) {
                calibrationCallbacks.add(callback)
            } else if (callback is ImageCallback) {
                imageCallbacks.add(callback)
            } else if (callback is StatusCallback) {
                statusCallbacks.add(callback)
            } else if (callback is UserStatusCallback) {
                userStatusCallbacks.add(callback)
            }
        }
    }

    fun removeCallbacks(vararg callbacks: GazeTrackerCallback?) {
        for (callback in callbacks) {
            gazeCallbacks.remove(callback)
            calibrationCallbacks.remove(callback)
            imageCallbacks.remove(callback)
            statusCallbacks.remove(callback)
        }
    }

    fun startGazeTracking(): Boolean {
        if (hasGazeTracker()) {
            localGazeTracker!!.startTracking()
            return true
        }
        return false
    }

    fun stopGazeTracking(): Boolean {
        if (isTracking) {
            localGazeTracker!!.stopTracking()
            return true
        }
        return false
    }

    fun startCalibration(modeType: CalibrationModeType?, criteria: AccuracyCriteria?): Boolean {
        return if (hasGazeTracker()) {
            localGazeTracker!!.startCalibration(modeType, criteria)
        } else false
    }

    fun stopCalibration(): Boolean {
        if (isCalibrating) {
            localGazeTracker!!.stopCalibration()
            return true
        }
        return false
    }

    fun startCollectingCalibrationSamples(): Boolean {
        return if (isCalibrating) {
            localGazeTracker!!.startCollectSamples()
        } else false
    }

    val isTracking: Boolean
        get() = if (hasGazeTracker()) {
            localGazeTracker!!.isTracking
        } else false
    val isCalibrating: Boolean
        get() = if (hasGazeTracker()) {
            localGazeTracker!!.isCalibrating
        } else false

    enum class LoadCalibrationResult {
        SUCCESS, FAIL_DOING_CALIBRATION, FAIL_NO_CALIBRATION_DATA, FAIL_HAS_NO_TRACKER
    }

    fun loadCalibrationData(): LoadCalibrationResult {
        if (!hasGazeTracker()) {
            return LoadCalibrationResult.FAIL_HAS_NO_TRACKER
        }
        val calibrationData = CalibrationDataStorage.loadCalibrationData(mContext.get())
        return if (calibrationData != null) {
            if (!localGazeTracker!!.setCalibrationData(calibrationData)) {
                LoadCalibrationResult.FAIL_DOING_CALIBRATION
            } else {
                LoadCalibrationResult.SUCCESS
            }
        } else {
            LoadCalibrationResult.FAIL_NO_CALIBRATION_DATA
        }
    }

    fun setCameraPreview(preview: TextureView) {
        cameraPreview = WeakReference(preview)
        if (hasGazeTracker()) {
            localGazeTracker!!.setCameraPreview(preview)
        }
    }

    fun removeCameraPreview(preview: TextureView) {
        if (cameraPreview!!.get() === preview) {
            cameraPreview = null
            if (hasGazeTracker()) {
                localGazeTracker!!.removeCameraPreview()
            }
        }
    }

    // GazeTracker Callbacks
    private val initializationCallback =
        InitializationCallback { gazeTracker, initializationErrorType ->
            setGazeTracker(gazeTracker)
            for (initializationCallback in initializationCallbacks) {
                initializationCallback.onInitialized(gazeTracker, initializationErrorType)
            }
            initializationCallbacks.clear()
            if (gazeTracker != null) {
                gazeTracker.setCallbacks(
                    gazeCallback,
                    calibrationCallback,
                    imageCallback,
                    statusCallback,
                    userStatusCallback
                )
                if (cameraPreview != null) {
                    gazeTracker.setCameraPreview(cameraPreview!!.get())
                }
            }
        }
    private val gazeCallback = GazeCallback { gazeInfo ->
        for (gazeCallback in gazeCallbacks) {
            gazeCallback.onGaze(gazeInfo)
        }
    }
    private val userStatusCallback: UserStatusCallback = object : UserStatusCallback {
        override fun onAttention(timestampBegin: Long, timestampEnd: Long, attentionScore: Float) {
            for (userStatusCallback in userStatusCallbacks) {
                userStatusCallback.onAttention(timestampBegin, timestampEnd, attentionScore)
            }
        }

        override fun onBlink(
            timestamp: Long,
            isBlinkLeft: Boolean,
            isBlinkRight: Boolean,
            isBlink: Boolean,
            eyeOpenness: Float
        ) {
            for (userStatusCallback in userStatusCallbacks) {
                userStatusCallback.onBlink(
                    timestamp,
                    isBlinkLeft,
                    isBlinkRight,
                    isBlink,
                    eyeOpenness
                )
            }
        }

        override fun onDrowsiness(timestamp: Long, isDrowsiness: Boolean) {
            for (userStatusCallback in userStatusCallbacks) {
                userStatusCallback.onDrowsiness(timestamp, isDrowsiness)
            }
        }
    }
    private val calibrationCallback: CalibrationCallback = object : CalibrationCallback {
        override fun onCalibrationProgress(v: Float) {
            for (calibrationCallback in calibrationCallbacks) {
                calibrationCallback.onCalibrationProgress(v)
            }
        }

        override fun onCalibrationNextPoint(v: Float, v1: Float) {
            for (calibrationCallback in calibrationCallbacks) {
                calibrationCallback.onCalibrationNextPoint(v, v1)
            }
        }

        override fun onCalibrationFinished(doubles: DoubleArray) {
            CalibrationDataStorage.saveCalibrationData(mContext.get(), doubles)
            for (calibrationCallback in calibrationCallbacks) {
                calibrationCallback.onCalibrationFinished(doubles)
            }
        }
    }
    private val imageCallback = ImageCallback { l, bytes ->
        for (imageCallback in imageCallbacks) {
            imageCallback.onImage(l, bytes)
        }
    }
    private val statusCallback: StatusCallback = object : StatusCallback {
        override fun onStarted() {
            for (statusCallback in statusCallbacks) {
                statusCallback.onStarted()
            }
        }

        override fun onStopped(statusErrorType: StatusErrorType) {
            for (statusCallback in statusCallbacks) {
                statusCallback.onStopped(statusErrorType)
            }
        }
    }

    private fun setGazeTracker(gazeTracker: GazeTracker) {
        this.localGazeTracker = gazeTracker
    }

    companion object {
        var instance: GazeTrackerManager? = null
            private set

        fun makeNewInstance(context: Context): GazeTrackerManager? {
            if (instance != null) {
                instance!!.deinitGazeTracker()
            }
            instance = GazeTrackerManager(context)
            return instance
        }
    }
}