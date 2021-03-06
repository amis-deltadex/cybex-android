package com.cybexmobile.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.CardView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.cybexmobile.BuildConfig;
import com.cybexmobile.helper.ActionBarTitleHelper;
import com.cybexmobile.helper.StoreLanguageHelper;
import com.cybexmobile.R;
import com.g00fy2.versioncompare.Version;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SettingActivity extends AppCompatActivity {

    private CardView mLanguageSettingView, mThemeSettingView, mSettingVersionView;

    private Handler mHandler = new Handler();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(updateResources(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        ActionBarTitleHelper.centeredActionBarTitle(this);
        setBackButton();
        initViews();
        onClickListener();
        displayLanguage();
        displayTheme();
        displayVersionNumber();
        if (getSupportActionBar() != null) {
            ActionBar supportActionBar = getSupportActionBar();
            TextView titile = supportActionBar.getCustomView().findViewById(R.id.actionbar_title);
            titile.setText(getResources().getString(R.string.title_setting));
        }
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(String string) {
        switch (string) {
            case "THEME_CHANGED":
                recreate();
                break;
            case "EVENT_REFRESH_LANGUAGE":
                updateResources(getBaseContext());
                recreate();
                break;
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    private void setBackButton() {
        ImageView backButton;
        TextView mTitile;
        if (getSupportActionBar() != null) {
            backButton = getSupportActionBar().getCustomView().findViewById(R.id.action_bar_arrow_back_button);
            mTitile = getSupportActionBar().getCustomView().findViewById(R.id.actionbar_title);
            backButton.setVisibility(View.VISIBLE);
            mTitile.setText(getResources().getString(R.string.title_setting));
            backButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }
    }

    private void initViews() {
        mLanguageSettingView = findViewById(R.id.setting_language);
        mThemeSettingView = findViewById(R.id.setting_theme);
        mSettingVersionView = findViewById(R.id.setting_version);
    }

    private void onClickListener() {
        mLanguageSettingView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingActivity.this, ChooseLanguageActivity.class);
                startActivity(intent);
            }
        });

        mThemeSettingView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingActivity.this, ChooseThemeActivity.class);
                startActivity(intent);
            }
        });

        mSettingVersionView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkIfNeedToUpdate();
            }
        });
    }

    private void displayLanguage() {
        String defaultLanguage = StoreLanguageHelper.getLanguageLocal(this);
        TextView textView = mLanguageSettingView.findViewById(R.id.setting_language_type);
        if (defaultLanguage != null) {
            if (defaultLanguage.equals("en")) {
                textView.setText(getResources().getString(R.string.setting_language_english));
            } else if (defaultLanguage.equals("zh")) {
                textView.setText(getResources().getString(R.string.setting_language_chinese));
            }

        }
    }

    private void displayTheme() {
        boolean isNight = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("night_mode", false);
        TextView textView = (TextView) mThemeSettingView.findViewById(R.id.setting_theme_content);
        if (isNight) {
            textView.setText(getResources().getString(R.string.setting_theme_light));
        } else {
            textView.setText(getResources().getString(R.string.setting_theme_dark));
        }

    }

    private void displayVersionNumber() {
        String versionName = BuildConfig.VERSION_NAME;
        TextView versionNumber = mSettingVersionView.findViewById(R.id.setting_version_content);
        versionNumber.setText(getResources().getString(R.string.setting_version_value, versionName));
    }

    private void checkIfNeedToUpdate() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("https://cybex.io/Android_update.json")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                call.cancel();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String versionResponse = response.body().string();
                JSONObject jsonObject;
                try {
                    jsonObject = new JSONObject(versionResponse);
                    String versionName = jsonObject.getString("version");
                    final String updateUrl = jsonObject.getString("url");
                    final AlertDialog.Builder builder = new AlertDialog.Builder(SettingActivity.this);
                    Version localVersion = new Version(BuildConfig.VERSION_NAME);
                    Version remoteVersion = new Version(versionName);
                    if (localVersion.isLowerThan(remoteVersion)) {
                        builder.setCancelable(false);
                        builder.setTitle("Update Available");
                        builder.setMessage("A new version of CybexDex is available. Please update to newest version now");
                        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent browseIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                                startActivity(browseIntent);
                            }
                        });
                        builder.setNegativeButton("NextTime", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                //if user select "No", just cancel this dialog and continue with app
                                dialog.cancel();
                            }
                        });

                    } else {
                        builder.setCancelable(false);
                        builder.setTitle("No Update Available");
                        builder.setMessage("the current version is the latest one");
                        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.cancel();
                            }
                        });
                    }
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog alert = builder.create();
                            alert.show();
                        }
                    });
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private Context updateResources(Context context) {
        String language = StoreLanguageHelper.getLanguageLocal(context);
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        context = context.createConfigurationContext(config);
        return context;
    }


}
