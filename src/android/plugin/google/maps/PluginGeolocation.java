package plugin.google.maps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class PluginGeolocation extends CordovaPlugin {
  private Activity activity;
  private final String TAG = "PluginGeolocation";
  private HashMap<String, Bundle> bufferForLocationDialog = new HashMap<String, Bundle>();

  private final int ACTIVITY_LOCATION_DIALOG = 0x7f999900; // Invite the location dialog using Google Play Services
  private final int ACTIVITY_LOCATION_PAGE = 0x7f999901;   // Open the location settings page

  private GoogleApiClient googleApiClient = null;
  public static final HashMap<String, String> semaphore = new HashMap<String, String>();

  public void initialize(final CordovaInterface cordova, final CordovaWebView webView) {
    super.initialize(cordova, webView);
    activity = cordova.getActivity();
  }


  @Override
  public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {

    cordova.getThreadPool().submit(new Runnable() {
      @Override
      public void run() {
        try {
          if ("getMyLocation".equals(action)) {
            PluginGeolocation.this.getMyLocation(args, callbackContext);
          }

        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
    return true;

  }

  @SuppressWarnings("unused")
  public void getMyLocation(final JSONArray args, final CallbackContext callbackContext) throws JSONException {

    // enableHighAccuracy = true -> PRIORITY_HIGH_ACCURACY
    // enableHighAccuracy = false -> PRIORITY_BALANCED_POWER_ACCURACY

    JSONObject params = args.getJSONObject(0);
    boolean isHighLocal = false;
    if (params.has("enableHighAccuracy")) {
      isHighLocal = params.getBoolean("enableHighAccuracy");
    }
    final boolean isHigh = isHighLocal;

    // Request geolocation permission.
    boolean locationPermission = cordova.hasPermission("android.permission.ACCESS_COARSE_LOCATION");

    if (!locationPermission) {
      //_saveArgs = args;
      //_saveCallbackContext = callbackContext;
      synchronized (semaphore) {
        cordova.requestPermissions(this, callbackContext.hashCode(), new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"});
        try {
          semaphore.wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      locationPermission = cordova.hasPermission("android.permission.ACCESS_COARSE_LOCATION");

      if (!locationPermission) {
        callbackContext.error("Geolocation permission request was denied.");
        return;
      }
    }

    if (googleApiClient == null) {
      googleApiClient = new GoogleApiClient.Builder(activity)
        .addApi(LocationServices.API)
        .addConnectionCallbacks(new com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks() {

          @Override
          public void onConnected(Bundle connectionHint) {
            Log.e(TAG, "===> onConnected");
            PluginGeolocation.this.sendNoResult(callbackContext);

            _checkLocationSettings(isHigh, callbackContext);
          }

          @Override
          public void onConnectionSuspended(int cause) {
            Log.e(TAG, "===> onConnectionSuspended");
          }

        })
        .addOnConnectionFailedListener(new com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener() {

          @Override
          public void onConnectionFailed(@NonNull ConnectionResult result) {
            Log.e(TAG, "===> onConnectionFailed");

            PluginResult tmpResult = new PluginResult(PluginResult.Status.ERROR, result.toString());
            tmpResult.setKeepCallback(false);
            callbackContext.sendPluginResult(tmpResult);

            googleApiClient.disconnect();
          }

        })
        .build();
      googleApiClient.connect();
    } else if (googleApiClient.isConnected()) {
      _checkLocationSettings(isHigh, callbackContext);
    }
  }

  private void _checkLocationSettings(final boolean enableHighAccuracy, final CallbackContext callbackContext) {

    LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder().setAlwaysShow(true);

    LocationRequest locationRequest;
    locationRequest = LocationRequest.create()
        .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
    builder.addLocationRequest(locationRequest);

    if (enableHighAccuracy) {
      locationRequest = LocationRequest.create()
          .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
      builder.addLocationRequest(locationRequest);
    }

    PendingResult<LocationSettingsResult> locationSettingsResult =
        LocationServices.SettingsApi.checkLocationSettings(googleApiClient, builder.build());

    locationSettingsResult.setResultCallback(new ResultCallback<LocationSettingsResult>() {

      @Override
      public void onResult(@NonNull LocationSettingsResult result) {
        final Status status = result.getStatus();
        switch (status.getStatusCode()) {
          case LocationSettingsStatusCodes.SUCCESS:
            _requestLocationUpdate(false, enableHighAccuracy, callbackContext);
            break;

          case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
            // Location settings are not satisfied. But could be fixed by showing the user
            // a dialog.
            try {
              //Keep the callback id
              Bundle bundle = new Bundle();
              bundle.putInt("type", ACTIVITY_LOCATION_DIALOG);
              bundle.putString("callbackId", callbackContext.getCallbackId());
              bundle.putBoolean("enableHighAccuracy", enableHighAccuracy);
              int hashCode = bundle.hashCode();

              bufferForLocationDialog.put("bundle_" + hashCode, bundle);
              PluginGeolocation.this.sendNoResult(callbackContext);

              // Show the dialog by calling startResolutionForResult(),
              // and check the result in onActivityResult().
              cordova.setActivityResultCallback(PluginGeolocation.this);
              status.startResolutionForResult(cordova.getActivity(), hashCode);
            } catch (IntentSender.SendIntentException e) {
              // Show the dialog that is original version of this plugin.
              _showLocationSettingsPage(enableHighAccuracy, callbackContext);
            }
            break;

          case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
            // Location settings are not satisfied. However, we have no way to fix the
            // settings so we won't show the dialog.

            JSONObject jsResult = new JSONObject();
            try {
              jsResult.put("status", false);
              jsResult.put("error_code", "service_not_available");
              jsResult.put("error_message", "This app has been rejected to use Location Services.");
            } catch (JSONException e) {
              e.printStackTrace();
            }
            callbackContext.error(jsResult);
            break;
        }
      }

    });
  }

  private void _showLocationSettingsPage(final boolean enableHighAccuracy, final CallbackContext callbackContext) {
    //Ask the user to turn on the location services.
    AlertDialog.Builder builder = new AlertDialog.Builder(this.activity);
    builder.setTitle("Improve location accuracy");
    builder.setMessage("To enhance your Maps experience:\n\n" +
        " - Enable Google apps location access\n\n" +
        " - Turn on GPS and mobile network location");
    builder.setPositiveButton("Settings", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          //Keep the callback id
          Bundle bundle = new Bundle();
          bundle.putInt("type", ACTIVITY_LOCATION_PAGE);
          bundle.putString("callbackId", callbackContext.getCallbackId());
          bundle.putBoolean("enableHighAccuracy", enableHighAccuracy);
          int hashCode = bundle.hashCode();

          bufferForLocationDialog.put("bundle_" + hashCode, bundle);
          PluginGeolocation.this.sendNoResult(callbackContext);

          //Launch settings, allowing user to make a change
          cordova.setActivityResultCallback(PluginGeolocation.this);
          Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
          activity.startActivityForResult(intent, hashCode);
        }
    });
    builder.setNegativeButton("Skip", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          //No location service, no Activity
          dialog.dismiss();

          JSONObject result = new JSONObject();
          try {
            result.put("status", false);
            result.put("error_code", "service_denied");
            result.put("error_message", "This app has been rejected to use Location Services.");
          } catch (JSONException e) {
            e.printStackTrace();
          }
          callbackContext.error(result);
        }
    });
    builder.create().show();
  }

  @SuppressWarnings("MissingPermission")
  private void _requestLocationUpdate(final boolean isRetry, final boolean enableHighAccuracy, final CallbackContext callbackContext) {

    int priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
    if (enableHighAccuracy) {
      priority = LocationRequest.PRIORITY_HIGH_ACCURACY;
    }

    LocationRequest locationRequest= LocationRequest.create()
        .setExpirationTime(5000)
        .setNumUpdates(2)
        .setSmallestDisplacement(0)
        .setPriority(priority)
        .setInterval(5000);


    final PendingResult<Status> result =  LocationServices.FusedLocationApi.requestLocationUpdates(
        googleApiClient, locationRequest, new LocationListener() {

          @Override
          public void onLocationChanged(Location location) {
            /*
            if (callbackContext.isFinished()) {
              return;
            }
            */
            JSONObject result;
            try {
              result = PluginUtil.location2Json(location);
              result.put("status", true);
              callbackContext.success(result);
            } catch (JSONException e) {
              e.printStackTrace();
            }

            googleApiClient.disconnect();
          }

        });

    result.setResultCallback(new ResultCallback<Status>() {

      public void onResult(Status status) {
        if (!status.isSuccess()) {
          String errorMsg = status.getStatusMessage();
          PluginResult result = new PluginResult(PluginResult.Status.ERROR, errorMsg);
          callbackContext.sendPluginResult(result);
        } else {
          // no update location
          Location location = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
          if (location != null) {
            try {
              JSONObject result = PluginUtil.location2Json(location);
              result.put("status", true);
              callbackContext.success(result);
            } catch (JSONException e) {
              e.printStackTrace();
            }
          } else {
            if (!isRetry) {
              Toast.makeText(activity, "Waiting for location...", Toast.LENGTH_SHORT).show();

              PluginGeolocation.this.sendNoResult(callbackContext);

              // Retry
              Handler handler = new Handler();
              handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                  _requestLocationUpdate(true, enableHighAccuracy, callbackContext);
                }
              }, 3000);
            } else {
              // Send back the error result
              JSONObject result = new JSONObject();
              try {
                result.put("status", false);
                result.put("error_code", "cannot_detect");
                result.put("error_message", "Can not detect your location. Try again.");
              } catch (JSONException e) {
                e.printStackTrace();
              }
              callbackContext.error(result);
            }
          }
        }
      }
    });
  }

  private void _onActivityResultLocationPage(Bundle bundle) {
    String callbackId = bundle.getString("callbackId");
    CallbackContext callbackContext = new CallbackContext(callbackId, this.webView);

    LocationManager locationManager = (LocationManager) this.activity.getSystemService(Context.LOCATION_SERVICE);
    List<String> providers = locationManager.getAllProviders();
    int availableProviders = 0;
    //if (mPluginLayout != null && mPluginLayout.isDebug) {
      Log.d(TAG, "---debug at getMyLocation(available providers)--");
    //}
    Iterator<String> iterator = providers.iterator();
    String provider;
    boolean isAvailable;
    while(iterator.hasNext()) {
      provider = iterator.next();
      isAvailable = locationManager.isProviderEnabled(provider);
      if (isAvailable) {
        availableProviders++;
      }
      //if (mPluginLayout != null && mPluginLayout.isDebug) {
        Log.d(TAG, "   " + provider + " = " + (isAvailable ? "" : "not ") + "available");
      //}
    }
    if (availableProviders == 0) {
      JSONObject result = new JSONObject();
      try {
        result.put("status", false);
        result.put("error_code", "not_available");
        result.put("error_message", "Since this device does not have any location provider, this app can not detect your location.");
      } catch (JSONException e) {
        e.printStackTrace();
      }
      callbackContext.error(result);
      return;
    }

    _inviteLocationUpdateAfterActivityResult(bundle);
  }

  private void _inviteLocationUpdateAfterActivityResult(Bundle bundle) {
    boolean enableHighAccuracy = bundle.getBoolean("enableHighAccuracy");
    String callbackId = bundle.getString("callbackId");
    CallbackContext callbackContext = new CallbackContext(callbackId, this.webView);
    this._requestLocationUpdate(false, enableHighAccuracy, callbackContext);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (!bufferForLocationDialog.containsKey("bundle_" + requestCode)) {
      Log.e(TAG, "no key");
      return;
    }
    Bundle query = bufferForLocationDialog.get("bundle_" + requestCode);
    Log.d(TAG, "====> onActivityResult (" + resultCode + ")");

    switch (query.getInt("type")) {
      case ACTIVITY_LOCATION_DIALOG:
        // User was asked to enable the location setting.
        switch (resultCode) {
          case Activity.RESULT_OK:
            // All required changes were successfully made
            _inviteLocationUpdateAfterActivityResult(query);
            break;
          case Activity.RESULT_CANCELED:
            // The user was asked to change settings, but chose not to
            _userRefusedToUseLocationAfterActivityResult(query);
            break;
          default:
            break;
        }
        break;
      case ACTIVITY_LOCATION_PAGE:
        _onActivityResultLocationPage(query);
        break;
    }
  }
  private void _userRefusedToUseLocationAfterActivityResult(Bundle bundle) {
    String callbackId = bundle.getString("callbackId");
    CallbackContext callbackContext = new CallbackContext(callbackId, this.webView);
    JSONObject result = new JSONObject();
    try {
      result.put("status", false);
      result.put("error_code", "service_denied");
      result.put("error_message", "This app has been rejected to use Location Services.");
    } catch (JSONException e) {
      e.printStackTrace();
    }
    callbackContext.error(result);
  }

  public void onRequestPermissionResult(int requestCode, String[] permissions,
                                        int[] grantResults) throws JSONException {
    synchronized (semaphore) {
      semaphore.notify();
    }
  }

  protected void sendNoResult(CallbackContext callbackContext) {
    PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
    pluginResult.setKeepCallback(true);
    callbackContext.sendPluginResult(pluginResult);
  }
}