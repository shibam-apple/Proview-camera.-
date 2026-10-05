package app.proview.camera.device

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/**
 * Reads what Camera2 exposes about every camera. Reading characteristics needs no
 * camera permission, so this works before the user has granted access.
 */
class DeviceProbe(private val context: Context) {

    fun probe(): DeviceReport {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList.toMutableList()
        // Physical lenses behind a logical camera are often not listed on their own.
        val cameras = mutableListOf<CameraInfo>()
        val seen = mutableSetOf<String>()
        while (ids.isNotEmpty()) {
            val id = ids.removeAt(0)
            if (!seen.add(id)) continue
            val info = runCatching { read(id, manager.getCameraCharacteristics(id)) }.getOrNull() ?: continue
            cameras += info
            ids += info.physicalIds.filter { it !in seen }
        }
        return DeviceReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            cameras = cameras,
        )
    }

    private fun read(id: String, c: CameraCharacteristics): CameraInfo {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        fun largest(format: Int) = map?.getOutputSizes(format)
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?.let { it.width to it.height }

        return CameraInfo(
            id = id,
            facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
                CameraMetadata.LENS_FACING_BACK -> Facing.BACK
                else -> Facing.EXTERNAL
            },
            hardwareLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
                else -> HardwareLevel.LEGACY
            },
            supportsRaw = REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            supportsManualSensor = REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            supportsBurst = REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in caps,
            isLogicalMultiCamera = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps,
            physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.physicalCameraIds.toList() else emptyList(),
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            sensorWidthMm = physicalSize?.width ?: 0f,
            sensorHeightMm = physicalSize?.height ?: 0f,
            pixelArrayWidth = pixelArray?.width ?: 0,
            pixelArrayHeight = pixelArray?.height ?: 0,
            maxRawSize = largest(ImageFormat.RAW_SENSOR),
            maxYuvSize = largest(ImageFormat.YUV_420_888),
            isoRange = iso?.let { it.lower..it.upper },
            exposureRangeNs = exposure?.let { it.lower..it.upper },
        )
    }
}
