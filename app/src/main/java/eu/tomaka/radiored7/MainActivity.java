package eu.tomaka.radiored7;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import net.moraleboost.streamscraper.ScrapeException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import eu.tomaka.radiored7.helpers.ModifiedWebView;
import eu.tomaka.radiored7.helpers.ShoutcastParser;

public class MainActivity extends Activity implements View.OnClickListener {

    private MediaPlayer mp;
    private Button buttonSendGreetings;
    private Button buttonSeeSchedule;
    private Spinner spinnerRadioChannel;
    private HashMap<String, String> channelSCAddressHash = new HashMap<String, String>();
    private String chosenChannel = "Główny";
    private TextView streamGenre;
    private TextView streamTitle;
    private String generaLabel;
    private String titleLabel;
    private ImageView imageViewPlayPause;
    private Integer playerState = 0; // 0 = Stopped; 1 = Playing; 2 = Paused (currently not used)
    private HeadphoneUnplugReceiver mHeadphoneUnplugReceiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        spinnerRadioChannel = (Spinner) findViewById(R.id.spinnerRaioChannel);

        imageViewPlayPause = (ImageView) findViewById(R.id.imageViewPlayPause);
        buttonSendGreetings = (Button) findViewById(R.id.buttonSendGreetings);
        buttonSeeSchedule = (Button) findViewById(R.id.buttonSeeSchedule);
        streamGenre = (TextView) findViewById(R.id.textViewStreamGenere);
        streamTitle = (TextView) findViewById(R.id.textViewStreamTitle);

        imageViewPlayPause.setOnClickListener(this);
        buttonSendGreetings.setOnClickListener(this);
        buttonSeeSchedule.setOnClickListener(this);
        setButtonsState();
        setupShoutcastAddresses();

        // Listen for headphone unplug
        IntentFilter headphoneUnplugIntentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        mHeadphoneUnplugReceiver = new HeadphoneUnplugReceiver();
        registerReceiver(mHeadphoneUnplugReceiver, headphoneUnplugIntentFilter);

        //new thread to update stream title and genre every 30 seconds
        Thread t = new Thread() {

            public void run() {
                try {
                    while (!isInterrupted()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Log.d("Red7", "update genre");
                                reloadSCInfo();
                            }
                        });
                        Thread.sleep(30000);
                    }
                } catch (InterruptedException e) {
                }
            }
        };
        t.start();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        //unregister headphone unplug
        this.unregisterReceiver(mHeadphoneUnplugReceiver);
    }


    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        //super.onBackPressed();
        if (playerState==1) {
            stopRadio();
            Toast.makeText(this, getString(R.string.backToExit),
                    Toast.LENGTH_SHORT).show();
        } else {
            stopRadio();
            super.onBackPressed();
        }
    }

    public void onClick(View v) {
        if (v == imageViewPlayPause && playerState == 0) {
            startRadio();
        } else if (v == imageViewPlayPause && playerState == 1) {
            stopRadio();
        } else if (v == buttonSendGreetings) {
            startGreetingsWebView();
        } else if (v == buttonSeeSchedule) {
            startScheduleWebView();
        }
    }

    private void initializeMP() {
        mp = new MediaPlayer();
        try {
            mp.setDataSource(channelSCAddressHash.get(chosenChannel));
        } catch (IOException e) {
            Log.e("Red7", "Unable initialize player with address " + channelSCAddressHash.get(chosenChannel));
            e.printStackTrace();
        }
    }


    private void startRadio() {

        Log.d("Red7", "Radio started");

        final ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle(getString(R.string.buffering));
        progress.setMessage(getString(R.string.pleaseWait));
        progress.show();
        initializeMP();
        mp.prepareAsync();
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {

            public void onPrepared(MediaPlayer mp) {
                MainActivity.this.mp.start();
                progress.dismiss();

            }
        });
        playerState = 1;
        Log.d("PlayerState", playerState.toString());
        setButtonsState();

    }

    private void stopRadio() {
        Log.d("Red7", "Stop pressed");

        try {
            playerState = 0;
            setButtonsState();
            mp.stop();
            mp.release();
            Log.d("PlayerState", playerState.toString());

        } catch (Exception e) {
            Log.d("Red7", "Media player has already been released - do nothing");
        }

    }

    private void setupShoutcastAddresses() {
        channelSCAddressHash.put("Główny", "http://sluchaj.radiors.pl:19182");
        channelSCAddressHash.put("Disco-Polo", "http://sluchaj.radiors.pl:19206");
        List<String> channelList = new ArrayList(channelSCAddressHash.keySet());
        Collections.sort(channelList, Collections.reverseOrder());
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>
                (this, R.layout.custom_spinner, channelList);
        spinnerRadioChannel.setAdapter(dataAdapter);
        addListenerOnSpinnerItemSelection();

    }

    public void addListenerOnSpinnerItemSelection() {

        spinnerRadioChannel = (Spinner) findViewById(R.id.spinnerRaioChannel);
        spinnerRadioChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                chosenChannel = parent.getItemAtPosition(position).toString();
                //stop radio if playing
                stopRadio();
                initializeMP();
                reloadSCInfo();
                setButtonsState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void reloadSCInfo() {

        AsyncTaskRunner runner = new AsyncTaskRunner();
        runner.execute();

    }

    private void setButtonsState() {

        if (playerState == 0) {
            imageViewPlayPause.setImageResource(R.drawable.play);
        } else if (playerState == 1) {
            imageViewPlayPause.setImageResource(R.drawable.pause);

        }

    }

    private void startGreetingsWebView() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(getString(R.string.sendGreetings) + " " + getString(R.string.channel) + " " + chosenChannel);

        ModifiedWebView wv = new ModifiedWebView(this);
        WebSettings webSettings = wv.getSettings();
        webSettings.setJavaScriptEnabled(true);

        if (chosenChannel.equals("Główny")) {
            wv.loadUrl("http://panel.radiors.pl/pozdro.php?kanal=glowny");
        } else if (chosenChannel.equals("Fly")) {
            wv.loadUrl("http://panel.radiors.pl/pozdro.php?kanal=fly");
        } else if (chosenChannel.equals("Disco-Polo")) {
            wv.loadUrl("http://panel.radiors.pl/pozdro.php?kanal=disco");
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);

                return true;
            }
        });

        alert.setView(wv);
        alert.show();
    }
    private void startScheduleWebView() {
        Log.d("Red7", "tutaj");

        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(getString(R.string.Schedule) + " " + getString(R.string.channel) + " " + chosenChannel);

        ModifiedWebView wv = new ModifiedWebView(this);
        WebSettings webSettings = wv.getSettings();
        webSettings.setJavaScriptEnabled(true);

        if (chosenChannel.equals("Główny")) {
            wv.loadUrl("http://panel.radiors.pl/ramowka.php?kanal=glowny");
        } else if (chosenChannel.equals("Fly")) {
            wv.loadUrl("http://panel.radiors.pl/ramowka.php?kanal=fly");
        } else if (chosenChannel.equals("Disco-Polo")) {
            wv.loadUrl("http://panel.radiors.pl/ramowka.php?kanal=disco");
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);

                return true;
            }
        });

        alert.setView(wv);
        alert.show();
    }

    public class HeadphoneUnplugReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (playerState==1 && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.v("Red7", "Headphones unplugged. Stopping playback.");
                stopRadio();
            }
        }
    }

    private class AsyncTaskRunner extends AsyncTask<String, String, String> {


        @Override
        protected String doInBackground(String... params) {
            try {
                generaLabel = new ShoutcastParser().getStreamGenere(channelSCAddressHash.get(chosenChannel));
                titleLabel = new ShoutcastParser().getStreamTitle(channelSCAddressHash.get(chosenChannel));
            } catch (IOException e) {
                e.printStackTrace();
            } catch (URISyntaxException e) {
                e.printStackTrace();
            } catch (ScrapeException e) {
                e.printStackTrace();
            }
            return "ok";
        }


        @Override
        protected void onPostExecute(String result) {
            streamGenre.setText(getString(R.string.streamGenere) + " " + generaLabel);
            streamTitle.setText(getString(R.string.streamTitle) + " " + titleLabel);
        }

    }

}
