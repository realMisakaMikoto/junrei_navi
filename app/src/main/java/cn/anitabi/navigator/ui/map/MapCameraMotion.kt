package cn.anitabi.navigator.ui.map

import android.animation.ValueAnimator
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdate as AmapCameraUpdate
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.GoogleMap

internal fun GoogleMap.animateCameraRespectingMotion(update: CameraUpdate) {
    if (ValueAnimator.areAnimatorsEnabled()) animateCamera(update) else moveCamera(update)
}

internal fun AMap.animateCameraRespectingMotion(update: AmapCameraUpdate) {
    if (ValueAnimator.areAnimatorsEnabled()) animateCamera(update) else moveCamera(update)
}
