package com.example.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

private val appCheckInstalled = AtomicBoolean(false)

@SuppressLint("ApplySharedPref")
fun configureAppCheck(context: Context, intent: Intent?): Boolean {
    val appContext = context.applicationContext ?: context
    return try {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            val initialized = FirebaseApp.initializeApp(appContext)
            if (initialized == null && FirebaseApp.getApps(appContext).isEmpty()) {
                Log.e(TAG, "FirebaseApp.initializeApp returned null (missing google-services.json / google_app_id).")
                return false
            }
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = appContext.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit() ensures the secret is stored before DebugAppCheckProviderFactory reads it
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            }

            if (appCheckInstalled.compareAndSet(false, true)) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed.")
            }
        } else if (appCheckInstalled.compareAndSet(false, true)) {
            val resId = appContext.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                appContext.packageName
            )
            if (resId != 0) {
                val siteKey = appContext.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
        FirebaseApp.getApps(appContext).isNotEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
        false
    }
}

/**
 * Safe idempotent guard called by background services or AI providers before accessing [Firebase.ai],
 * preventing "Default FirebaseApp is not initialized" if a service starts before MainActivity.
 */
fun ensureFirebaseInitialized(context: Context): Boolean {
    val appContext = context.applicationContext ?: context
    if (FirebaseApp.getApps(appContext).isNotEmpty()) {
        return true
    }
    return configureAppCheck(appContext, null)
}
