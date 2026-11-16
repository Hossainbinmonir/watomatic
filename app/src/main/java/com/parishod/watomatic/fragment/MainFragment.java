package com.parishod.watomatic.fragment;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.parishod.watomatic.BuildConfig;
import com.parishod.watomatic.service.NotificationService;
import com.parishod.watomatic.R;
import com.parishod.watomatic.activity.about.AboutActivity;
import com.parishod.watomatic.activity.customreplyeditor.CustomReplyEditorActivity;
import com.parishod.watomatic.activity.history.ReplyHistoryActivity;
import com.parishod.watomatic.activity.rules.RuleDashboardActivity;
import com.parishod.watomatic.activity.settings.SettingsActivity;
import com.parishod.watomatic.model.CustomRepliesData;
import com.parishod.watomatic.model.preferences.PreferencesManager;
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase;
import com.parishod.watomatic.model.utils.Constants;
import com.parishod.watomatic.model.utils.CustomDialog;
import com.parishod.watomatic.model.utils.DbUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.CATEGORY_BROWSABLE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT;
import static android.content.Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER;
import static android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;
import static com.parishod.watomatic.model.utils.Constants.MIN_REPLIES_TO_ASK_APP_RATING;

public class MainFragment extends Fragment {

    private PreferencesManager preferencesManager;
    private CustomRepliesData customRepliesData;

    private ActivityResultLauncher<Intent> notificationListenerLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private MaterialSwitch autoRepliesSwitch;
    private TextView aiReplyText;
    private View view;
    BottomNavigationView bottomNav;
    Button editButton;
    private int gitHubReleaseNotesId = -1;
    private TextView multiRuleSummaryText;
    private TextView replyHistorySummaryText;
    private TextView homeStatusText;
    private final ExecutorService rulesExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notificationListenerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (isListenerEnabled(requireContext(), NotificationService.class)) {
                        Toast.makeText(requireContext(), "Permission Granted", Toast.LENGTH_LONG).show();
                        preferencesManager.setServicePref(true);
                    } else {
                        Toast.makeText(requireContext(), "Permission Denied", Toast.LENGTH_LONG).show();
                        preferencesManager.setServicePref(false);
                    }
                    setSwitchState();
                });

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) {
                        showPostNotificationPermissionDeniedSnackbar(autoRepliesSwitch);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_main_redesigned, container, false);

        customRepliesData = CustomRepliesData.getInstance(requireContext());
        preferencesManager = PreferencesManager.getPreferencesInstance(requireContext());

        // Assign Views
        aiReplyText = view.findViewById(R.id.ai_reply_text);
        autoRepliesSwitch = view.findViewById(R.id.switch_auto_replies);
        editButton = view.findViewById(R.id.btn_edit);
        bottomNav = view.findViewById(R.id.bottom_nav);
        homeStatusText = view.findViewById(R.id.tv_home_status);

        // Setup AI Reply
        aiReplyText.setText(customRepliesData.getTextToSendOrElse());

        // Setup Auto-replies switch
        autoRepliesSwitch.setChecked(preferencesManager.isServiceEnabled());
        autoRepliesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !isListenerEnabled(requireContext(), NotificationService.class)) {
                showPermissionsDialog();
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!isPostNotificationPermissionGranted()) {
                        checkNotificationPermission();
                        return;
                    }
                }
                preferencesManager.setServicePref(isChecked);
                setSwitchState();
            }
        });

        // Setup Edit button
        editButton.setOnClickListener(v -> openCustomReplyEditorActivity(v));

        // Setup Multi-Rule Auto Responder Card
        multiRuleSummaryText = view.findViewById(R.id.tv_multi_rule_summary);
        View multiRuleCard = view.findViewById(R.id.card_multi_rule_responder);
        View manageRulesBtn = view.findViewById(R.id.btn_manage_rules);
        if (multiRuleCard != null) {
            multiRuleCard.setOnClickListener(v -> openRuleDashboardActivity());
        }
        if (manageRulesBtn != null) {
            manageRulesBtn.setOnClickListener(v -> openRuleDashboardActivity());
        }

        // Setup Reply History Card
        replyHistorySummaryText = view.findViewById(R.id.tv_reply_history_summary);
        View replyHistoryCard = view.findViewById(R.id.card_reply_history);
        View viewHistoryBtn = view.findViewById(R.id.btn_view_history);
        if (replyHistoryCard != null) {
            replyHistoryCard.setOnClickListener(v -> openReplyHistoryActivity());
        }
        if (viewHistoryBtn != null) {
            viewHistoryBtn.setOnClickListener(v -> openReplyHistoryActivity());
        }

        // Setup Bottom Navigation
        bottomNav.setOnNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_atomatic) {
                // Already on this screen
                return true;
            } else if (itemId == R.id.navigation_rules) {
                openRuleDashboardActivity();
                return true;
            } else if (itemId == R.id.navigation_settings) {
                // Handle settings navigation
                loadSettingsActivity();
                return true;
            }
            return false;
        });

        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
                // Remove system bottom inset
                return insets.replaceSystemWindowInsets(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        0 // remove bottom
                );
            }
        });

        // MenuProvider replaces deprecated setHasOptionsMenu/onCreateOptionsMenu/onOptionsItemSelected
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                // Toolbar menu is inflated by MainActivity; nothing to inflate here.
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.about) {
                    openAboutActivity();
                    return true;
                } else if (item.getItemId() == R.id.setting) {
                    loadSettingsActivity();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        if (!isPostNotificationPermissionGranted()) {
            checkNotificationPermission();
        }

        return view;
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private boolean isPostNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void showPostNotificationPermissionDeniedSnackbar(View view) {
        Snackbar.make(view, requireActivity().getResources().getString(R.string.post_notification_permission_snackbar_text), Snackbar.LENGTH_INDEFINITE)
                .setAction(requireActivity().getResources().getString(R.string.post_notification_permission_snackbar_setting), view1 -> {
                    // Open app settings
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", view1.getContext().getPackageName(), null);
                    intent.setData(uri);
                    view1.getContext().startActivity(intent);
                }).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(bottomNav != null){
            bottomNav.setSelectedItemId(R.id.navigation_atomatic);
        }
        //If user directly goes to Settings and removes notifications permission
        //when app is launched check for permission and set appropriate app state
        if (!isListenerEnabled(requireContext(), NotificationService.class)) {
            preferencesManager.setServicePref(false);
        }

        setSwitchState();

        // Set user auto reply text
        aiReplyText.setText(customRepliesData.getTextToSendOrElse());

        updateMultiRuleSummary();
        updateReplyHistorySummary();
        showAppRatingPopup();
    }

    private void openRuleDashboardActivity() {
        startActivity(new Intent(requireActivity(), RuleDashboardActivity.class));
    }

    private void openReplyHistoryActivity() {
        startActivity(new Intent(requireActivity(), ReplyHistoryActivity.class));
    }

    private void updateMultiRuleSummary() {
        if (multiRuleSummaryText == null || getContext() == null) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        rulesExecutor.execute(() -> {
            try {
                AutoReplyRulesDatabase db = AutoReplyRulesDatabase.getInstance(appContext);
                int total = db.ruleDao().getTotalRuleCountSync();
                int active = db.ruleDao().getEnabledRuleCountSync();
                if (isAdded() && multiRuleSummaryText != null) {
                    multiRuleSummaryText.post(() -> {
                        if (!isAdded()) return;
                        if (total == 0) {
                            multiRuleSummaryText.setText(R.string.multi_rule_card_summary_empty);
                        } else {
                            multiRuleSummaryText.setText(getString(R.string.multi_rule_card_summary, active, total));
                        }
                    });
                }
            } catch (Exception e) {
                Log.w("MainFragment", "Failed to load rule summary", e);
            }
        });
    }

    private void updateReplyHistorySummary() {
        if (replyHistorySummaryText == null || getContext() == null) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        rulesExecutor.execute(() -> {
            try {
                DbUtils utils = new DbUtils(appContext);
                long totalReplies = utils.getNumReplies();
                int uniquePairs = utils.getUniqueReplyPairCount();
                if (isAdded() && replyHistorySummaryText != null) {
                    replyHistorySummaryText.post(() -> {
                        if (!isAdded()) return;
                        if (totalReplies == 0L) {
                            replyHistorySummaryText.setText(R.string.reply_history_summary_empty);
                        } else {
                            replyHistorySummaryText.setText(
                                    getString(R.string.reply_history_summary_counts, totalReplies, uniquePairs)
                            );
                        }
                    });
                }
            } catch (Exception e) {
                Log.w("MainFragment", "Failed to load reply history summary", e);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        rulesExecutor.shutdown();
    }

    private void showAppRatingPopup() {
        boolean isFromStore = isAppInstalledFromStore(requireActivity());
        String status = preferencesManager.getPlayStoreRatingStatus();
        long ratingLastTime = preferencesManager.getPlayStoreRatingLastTime();
        if (isFromStore && !status.equals("Not Interested") && !status.equals("DONE") && ((System.currentTimeMillis() - ratingLastTime) > (10 * 24 * 60 * 60 * 1000L))) {
            if (isAppUsedSufficientlyToAskRating()) {
                CustomDialog customDialog = new CustomDialog(requireActivity());
                customDialog.showAppLocalRatingDialog(v -> showFeedbackPopup((int) v.getTag()));
                preferencesManager.setPlayStoreRatingLastTime(System.currentTimeMillis());
            }
        }
    }

    //REF: https://stackoverflow.com/questions/37539949/detect-if-an-app-is-installed-from-play-store
    public static boolean isAppInstalledFromStore(Context context) {
        // A list with valid installers package name
        List<String> validInstallers = new ArrayList<>(Arrays.asList("com.android.vending", "com.google.android.feedback"));

        try {
            final String installer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installer = context.getPackageManager()
                        .getInstallSourceInfo(context.getPackageName())
                        .getInstallingPackageName();
            } else {
                installer = context.getPackageManager()
                        .getInstallerPackageName(context.getPackageName());
            }

            // true if your app has been downloaded from Play Store
            return installer != null && validInstallers.contains(installer);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAppUsedSufficientlyToAskRating() {
        DbUtils dbUtils = new DbUtils(requireActivity());
        long firstRepliedTime = dbUtils.getFirstRepliedTime();
        return firstRepliedTime > 0 && System.currentTimeMillis() - firstRepliedTime > 2 * 24 * 60 * 60 * 1000L && dbUtils.getNumReplies() >= MIN_REPLIES_TO_ASK_APP_RATING;
    }

    private void showFeedbackPopup(int rating) {
        CustomDialog customDialog = new CustomDialog(requireActivity());
        customDialog.showAppRatingDialog(rating, v -> {
            String tag = (String) v.getTag();
            if (tag.equals(requireActivity().getResources().getString(R.string.app_rating_goto_store_dialog_button1_title))) {
                //not interested
                preferencesManager.setPlayStoreRatingStatus("Not Interested");
            } else if (tag.equals(requireActivity().getResources().getString(R.string.app_rating_goto_store_dialog_button2_title))) {
                //Launch playstore rating page
                rateApp();
            } else if (tag.equals(requireActivity().getResources().getString(R.string.app_rating_feedback_dialog_mail_button_title))) {
                launchEmailCompose();
            } else if (tag.equals(requireActivity().getResources().getString(R.string.app_rating_feedback_dialog_telegram_button_title))) {
                launchFeedbackApp();
            }
        });
    }

    private void launchEmailCompose() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setDataAndType(Uri.parse("mailto:"), "plain/text"); // only email apps should handle this
        intent.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{Constants.EMAIL_ADDRESS});
        intent.putExtra(Intent.EXTRA_SUBJECT, Constants.EMAIL_SUBJECT);
        if (intent.resolveActivity(requireActivity().getPackageManager()) != null) {
            startActivity(intent);
        }
    }

    private void launchFeedbackApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            launchAppLegacy();
            return;
        }
        boolean isLaunched;
        try {
            // In order for this intent to be invoked, the system must directly launch a non-browser app.
            // Ref: https://developer.android.com/training/package-visibility/use-cases#avoid-a-disambiguation-dialog
            Intent intent = new Intent(ACTION_VIEW, Uri.parse(Constants.TELEGRAM_URL))
                    .addCategory(CATEGORY_BROWSABLE)
                    .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REQUIRE_NON_BROWSER |
                            FLAG_ACTIVITY_REQUIRE_DEFAULT);
            requireActivity().startActivity(intent);
            isLaunched = true;
        } catch (ActivityNotFoundException e) {
            // This code executes in one of the following cases:
            // 1. Only browser apps can handle the intent.
            // 2. The user has set a browser app as the default app.
            // 3. The user hasn't set any app as the default for handling this URL.
            isLaunched = false;
        }
        if (!isLaunched) { // Open Github latest release url in browser if everything else fails
            String url = getString(R.string.watomatic_github_latest_release_url);
            requireActivity().startActivity(new Intent(ACTION_VIEW).setData(Uri.parse(url)));
        }
    }

    private void launchAppLegacy() {
        if (getActivity() != null) {
            Intent intent = new Intent(ACTION_VIEW, Uri.parse(Constants.TELEGRAM_URL));
            List<ResolveInfo> list = getActivity().getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> possibleBrowserIntents = getActivity().getPackageManager()
                    .queryIntentActivities(new Intent(ACTION_VIEW, Uri.parse("http://www.deekshith.in/")), PackageManager.MATCH_DEFAULT_ONLY);
            Set<String> excludeIntents = new HashSet<>();
            for (ResolveInfo eachPossibleBrowserIntent : possibleBrowserIntents) {
                excludeIntents.add(eachPossibleBrowserIntent.activityInfo.name);
            }
            //Check for non browser application
            for (ResolveInfo resolveInfo : list) {
                if (!excludeIntents.contains(resolveInfo.activityInfo.name)) {
                    intent.setPackage(resolveInfo.activityInfo.packageName);
                    requireActivity().startActivity(intent);
                    break;
                }
            }
        }
    }

    /*
     * Start with rating the app
     * Determine if the Play Store is installed on the device
     *
     * */
    public void rateApp() {
        try {
            Intent rateIntent = rateIntentForUrl("market://details");
            startActivity(rateIntent);
        } catch (ActivityNotFoundException e) {
            Intent rateIntent = rateIntentForUrl("https://play.google.com/store/apps/details");
            startActivity(rateIntent);
        }
    }

    private Intent rateIntentForUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format("%s?id=%s", url, BuildConfig.APPLICATION_ID)));
        int flags = Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
        intent.addFlags(flags);
        return intent;
    }

    private void setSwitchState() {
        boolean isEnabled = preferencesManager.isServiceEnabled();
        autoRepliesSwitch.setChecked(isEnabled);
        if (homeStatusText != null && getContext() != null) {
            homeStatusText.setText(isEnabled ? R.string.home_active_status : R.string.home_paused_status);
            int colorRes = isEnabled ? R.color.rule_active_accent : R.color.text_secondary;
            homeStatusText.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        }
    }

    //https://stackoverflow.com/questions/20141727/check-if-user-has-granted-notificationlistener-access-to-my-app/28160115
    //TODO: Use in UI to verify if it needs enabling or restarting
    public boolean isListenerEnabled(Context context, Class notificationListenerCls) {
        ComponentName cn = new ComponentName(context, notificationListenerCls);
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private void openCustomReplyEditorActivity(View v) {
        Intent intent = new Intent(requireActivity(), CustomReplyEditorActivity.class);
        startActivity(intent);
    }

    private void openAboutActivity() {
        Intent intent = new Intent(requireActivity(), AboutActivity.class);
        startActivity(intent);
    }

    private void showPermissionsDialog() {
        CustomDialog customDialog = new CustomDialog(requireActivity());
        Bundle bundle = new Bundle();
        bundle.putString(Constants.PERMISSION_DIALOG_TITLE, getString(R.string.permission_dialog_title));
        bundle.putString(Constants.PERMISSION_DIALOG_MSG, getString(R.string.permission_dialog_msg));
        customDialog.showDialog(bundle, null, (dialog, which) -> {
            if (which == -2) {
                //Decline
                showPermissionDeniedDialog();
            } else {
                //Accept
                launchNotificationAccessSettings();
            }
        });
    }

    private void showPermissionDeniedDialog() {
        CustomDialog customDialog = new CustomDialog(requireActivity());
        Bundle bundle = new Bundle();
        bundle.putString(Constants.PERMISSION_DIALOG_DENIED_TITLE, getString(R.string.permission_dialog_denied_title));
        bundle.putString(Constants.PERMISSION_DIALOG_DENIED_MSG, getString(R.string.permission_dialog_denied_msg));
        bundle.putBoolean(Constants.PERMISSION_DIALOG_DENIED, true);
        customDialog.showDialog(bundle, null, (dialog, which) -> {
            if (which == -2) {
                //Decline
                setSwitchState();
            } else {
                //Accept
                launchNotificationAccessSettings();
            }
        });
    }

    public void launchNotificationAccessSettings() {
        //We should remove it few versions later
        enableService(true);//we need to enable the service for it so show in settings

        final String NOTIFICATION_LISTENER_SETTINGS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            NOTIFICATION_LISTENER_SETTINGS = ACTION_NOTIFICATION_LISTENER_SETTINGS;
        } else {
            NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
        }
        Intent i = new Intent(NOTIFICATION_LISTENER_SETTINGS);
        notificationListenerLauncher.launch(i);
    }

    private void enableService(boolean enable) {
        PackageManager packageManager = requireActivity().getPackageManager();
        ComponentName componentName = new ComponentName(requireActivity(), NotificationService.class);
        int settingCode = enable
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        // enable dummyActivity (as it is disabled in the manifest.xml)
        packageManager.setComponentEnabledSetting(componentName, settingCode, PackageManager.DONT_KILL_APP);

    }

    private void loadSettingsActivity() {
        Intent intent = new Intent(requireActivity(), SettingsActivity.class);
        requireActivity().startActivity(intent);
    }
}
