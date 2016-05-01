package com.example.android.sunshine.mobile.wear;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.mobile.Utility;
import com.example.android.sunshine.mobile.data.WeatherContract;
import com.example.android.sunshine.mobile.sync.SunshineSyncAdapter;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;

/**
 * Created by dan on 4/9/16.
 */
public class WearWeatherService extends IntentService
        implements MessageApi.MessageListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String[] FORECAST_COLUMNS = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };
    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    private static final String TAG = "WearWeatherService";
    private GoogleApiClient mGoogleApiClient;

    /**
     * The path for the {@link DataItem} containing sunshine weather.
     */
    private static final String PATH_WITH_WEATHER = "/sun_face/weather";
    private static final String PATH_WITH_QUERY = "/sun_face/query";
    private static final String MAX_TEMP_KEY = "hi";
    private static final String MIN_TEMP_KEY = "lo";
    private static final String WEATHER_ID_KEY = "id";

    private int mPreviousWeatherID;
    private int mPreviousMaxTemp;
    private int mPreviousMinTemp;

    public WearWeatherService() { super(TAG);}

    @Override
    public void onCreate() {
        super.onCreate();
        android.os.Debug.waitForDebugger();  // this line is key
        Log.d("WearWeatherService", "onCreate - WearWeatherService");

        mGoogleApiClient = new GoogleApiClient.Builder(this).
                addApi(Wearable.API).
                addConnectionCallbacks(this).
                addOnConnectionFailedListener(this).
                build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Wearable.MessageApi.addListener( mGoogleApiClient, this );
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    protected void onHandleIntent(Intent intent) {
        boolean dataUpdated = intent != null &&
                SunshineSyncAdapter.ACTION_DATA_UPDATED.equals(intent.getAction());
        if (dataUpdated) {
            updateWearWeather(null);
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (messageEvent.getPath().equalsIgnoreCase(PATH_WITH_QUERY))
        {
            updateWearWeather(messageEvent.getSourceNodeId());
        }
    }

    public void updateWearWeather(String destinationNode)
    {
        String location = Utility.getPreferredLocation(this);
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                location, System.currentTimeMillis());
        Cursor cursor = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null,
                null, WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            int maxTemp = cursor.getInt(INDEX_MAX_TEMP);
            int minTemp = cursor.getInt(INDEX_MIN_TEMP);

            // TODO Only update the weather if it changed from last time,
            mPreviousWeatherID = weatherId;
            mPreviousMinTemp = minTemp;
            mPreviousMaxTemp = maxTemp;

            JSONObject weatherMsg = new JSONObject();

            try {
                weatherMsg.put(WEATHER_ID_KEY, weatherId);
                weatherMsg.put(MIN_TEMP_KEY, minTemp);
                weatherMsg.put(MAX_TEMP_KEY, maxTemp);

                // Send to all connected nodes if none specified
                if (destinationNode == null) {
                    NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
                    for (Node node : nodes.getNodes()) {
                        Wearable.MessageApi.sendMessage(
                                mGoogleApiClient, node.getId(), PATH_WITH_WEATHER, weatherMsg.toString().getBytes("utf-8"));
                    }
                }
                else {
                    Wearable.MessageApi.sendMessage(
                            mGoogleApiClient, destinationNode, PATH_WITH_WEATHER, weatherMsg.toString().getBytes("utf-8"));
                }
            } catch (JSONException | UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        cursor.close();

    }
}
