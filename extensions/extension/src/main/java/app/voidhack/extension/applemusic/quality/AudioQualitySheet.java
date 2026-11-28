package app.voidhack.extension.applemusic.quality;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.hardware.usb.UsbDevice;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Shows the current output and the signal path, with output controls and alternatives on demand.
 */
final class AudioQualitySheet {
    // Apple Dark Mode neutral palette — flat, monochrome, no gradients or glows.
    private static final int SURFACE_SHEET = 0xFF1C1C1E;
    private static final int SURFACE_CARD = 0xFF262629;
    private static final int SURFACE_ELEVATED = 0xFF2C2C2E;
    private static final int STROKE_HAIRLINE = 0x18FFFFFF;
    private static final int STROKE_CIRCLE = 0x22FFFFFF;
    private static final int CONNECTOR_LINE = 0x38FFFFFF;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SPEC = 0xFFDCDCE2;
    private static final int TEXT_SECONDARY = 0xFF98989F;
    private static final int TEXT_TERTIARY = 0xFF8E8E93;
    private static final int TEXT_DISABLED = 0xFF93939A;
    private static final int ACTIVE_DOT_GREEN = 0xFF30D158;
    private static final int APPLE_MUSIC_RED = 0xFFFA2D48;
    private static final int BT_PERM_REQ_CODE = 0x5648; // "VH"

    private static WeakReference<Dialog> currentDialog = new WeakReference<>(null);

    private final Activity activity;
    private final WeakReference<View> badgeViewRef;
    private final Dialog dialog;
    private final FrameLayout scrim;
    private final LinearLayout sheet;
    private final TextView trackSubtitle;
    private final TextView currentOutputTitle;
    private final TextView currentOutputDescription;
    private final SignalPathContainer signalCard;
    private final View outputOptionsButton;
    private final ImageView outputOptionsChevron;
    private final LinearLayout outputOptions;
    private final LinearLayout pathsListContainer;
    private final LinearLayout usbDirectToggleRow;
    private final TextView usbDirectToggleTitle;
    private final TextView usbDirectToggleSubtitle;
    private final AppleSwitchView usbDirectToggleSwitch;
    private final View hwVolDivider;
    private final LinearLayout hwVolContainer;
    private final TextView hwVolValue;
    private final AppleVolumeSliderView hwVolSlider;
    private final TextView hwVolSubtitle;
    private final View usbToggleDivider;
    private final LinearLayout usbToggleRow;
    private final TextView usbToggleTitle;
    private final TextView usbToggleSubtitle;
    private final AppleSwitchView usbToggleSwitch;
    private final LinearLayout btPermCard;
    private final LinearLayout infoBox;
    private final TextView infoBody;
    private final ScrollView scroll;

    // 3-stage signal path views
    private final NodeViews sourceNode;
    private final ConnectorGap conn12;
    private final NodeViews deviceNode;
    private final ConnectorGap conn23;
    private final NodeViews outputNode;

    private final Typeface fontRegular;
    private final Typeface fontMedium;
    private final Typeface fontSemibold;
    private final Typeface fontBold;

    private final int uiVariant;
    private final int uiBitDepth;
    private final int uiSampleRate;
    private final String uiCodec;
    private final int uiCapabilities;

    private String lastTrackTitle = "";
    private String lastTrackSubtitle = "";
    private boolean btPermRequestedOnce;
    private boolean infoExpanded;
    private boolean outputOptionsExpanded;
    private boolean dismissing;
    private boolean draggingHwVolume;
    private int currentHwVolMinDb256;
    private int currentHwVolMaxDb256;
    private final Runnable liveRefresh = this::refresh;

    static void show(
            Activity activity,
            View badgeView,
            int uiVariant,
            int uiBitDepth,
            int uiSampleRate,
            String uiCodec,
            int uiCapabilities) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Dialog existing = currentDialog.get();
        if (existing != null && existing.isShowing()) {
            try {
                existing.dismiss();
            } catch (Throwable ignored) {
            }
        }
        AudioQualitySheet sheet = new AudioQualitySheet(
                activity,
                badgeView,
                uiVariant,
                uiBitDepth,
                uiSampleRate,
                uiCodec,
                uiCapabilities
        );
        sheet.present();
    }

    private AudioQualitySheet(
            Activity activity,
            View badgeView,
            int uiVariant,
            int uiBitDepth,
            int uiSampleRate,
            String uiCodec,
            int uiCapabilities) {
        this.activity = activity;
        this.badgeViewRef = new WeakReference<>(badgeView);
        this.uiVariant = uiVariant;
        this.uiBitDepth = uiBitDepth;
        this.uiSampleRate = uiSampleRate;
        this.uiCodec = uiCodec;
        this.uiCapabilities = uiCapabilities;

        String[] initialMeta = AudioQualityController.resolveTrackMetadata(badgeView, activity);
        this.lastTrackTitle = initialMeta[0];
        this.lastTrackSubtitle = initialMeta[1];

        this.fontRegular = resolveFont("regular", Typeface.DEFAULT);
        this.fontMedium = resolveFont("medium", fontRegular);
        this.fontSemibold = resolveFont("semibold", Typeface.DEFAULT_BOLD);
        this.fontBold = resolveFont("bold", fontSemibold);

        int themeId = AudioQualityStrings.id(activity, "style", "AppTheme.FullScreenDialog");
        this.dialog = themeId != 0 ? new Dialog(activity, themeId) : new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        scrim = new FrameLayout(activity) {
            @Override
            public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus && !dismissing) {
                    AudioQualityMonitor.ensureBluetoothCodecReceiverRegistered(activity);
                    refresh();
                }
            }
        };
        scrim.setBackgroundColor(0x70000000);
        if (Build.VERSION.SDK_INT >= 29) scrim.setForceDarkAllowed(false);
        scrim.setOnClickListener(v -> dismissAnimated());

        int maxSheetHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.88f);
        sheet = new LinearLayout(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                        ? maxSheetHeight : Math.min(MeasureSpec.getSize(heightMeasureSpec), maxSheetHeight);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }
        };
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setBackground(buildSheetBackground());
        sheet.setClipToOutline(true);
        sheet.setElevation(dp(16));

        if (Build.VERSION.SDK_INT >= 30) {
            scrim.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                scrim.setPadding(bars.left, bars.top, bars.right, 0);
                sheet.setPadding(0, 0, 0, Math.max(bars.bottom, dp(12)));
                return insets;
            });
        } else {
            sheet.setPadding(0, 0, 0, dp(16));
        }

        // Top drag handle + header bar
        LinearLayout headerWrap = new LinearLayout(activity);
        headerWrap.setOrientation(LinearLayout.VERTICAL);
        installDragToDismiss(headerWrap);

        View grabber = new View(activity);
        grabber.setBackground(solidPill(0x38FFFFFF));
        LinearLayout.LayoutParams grabParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        grabParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabParams.topMargin = dp(10);
        grabParams.bottomMargin = dp(6);
        headerWrap.addView(grabber, grabParams);

        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(20), dp(2), dp(16), dp(6));

        TextView headerLabel = label(AudioQualityStrings.text(activity, "header"), 17, fontSemibold, TEXT_PRIMARY);
        headerLabel.setAccessibilityHeading(true);
        headerRow.addView(headerLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageButton infoButton = circleActionButton(
                AudioQualityIcons.create(AudioQualityIcons.Glyph.INFO, TEXT_PRIMARY, dp(17)),
                AudioQualityStrings.text(activity, "info"),
                v -> toggleInfoBox()
        );
        headerRow.addView(infoButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        View spacer = new View(activity);
        headerRow.addView(spacer, new LinearLayout.LayoutParams(dp(4), 1));

        Drawable closeIcon = loadAppleDrawable("sf_xmark_monochrome_medium_medium", TEXT_PRIMARY);
        if (closeIcon == null) {
            closeIcon = AudioQualityIcons.create(AudioQualityIcons.Glyph.CLOSE, TEXT_PRIMARY, dp(15));
        }
        ImageButton closeButton = circleActionButton(
                closeIcon,
                AudioQualityStrings.text(activity, "close"),
                v -> dismissAnimated()
        );
        headerRow.addView(closeButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        headerWrap.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(headerWrap, new LinearLayout.LayoutParams(-1, -2));

        // Scrollable content
        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), 0, dp(20), dp(10));

        trackSubtitle = label("", 13, fontRegular, TEXT_SECONDARY);
        trackSubtitle.setSingleLine(true);
        trackSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        trackSubtitle.setPadding(dp(1), 0, 0, dp(12));
        body.addView(trackSubtitle);

        LinearLayout outputSummary = new LinearLayout(activity);
        outputSummary.setOrientation(LinearLayout.VERTICAL);
        outputSummary.setBackground(flatRounded(SURFACE_CARD, STROKE_HAIRLINE, 16));
        outputSummary.setPadding(dp(16), dp(14), dp(16), dp(14));
        body.addView(outputSummary, new LinearLayout.LayoutParams(-1, -2));

        outputSummary.addView(label(AudioQualityStrings.text(activity, "current_output"), 12, fontMedium, TEXT_SECONDARY));
        currentOutputTitle = label("", 22, fontBold, TEXT_PRIMARY);
        currentOutputTitle.setAccessibilityHeading(true);
        currentOutputTitle.setPadding(0, dp(3), 0, 0);
        outputSummary.addView(currentOutputTitle);
        currentOutputDescription = label("", 13, fontRegular, TEXT_SPEC);
        currentOutputDescription.setLineSpacing(dp(2), 1f);
        currentOutputDescription.setPadding(0, dp(4), 0, 0);
        outputSummary.addView(currentOutputDescription);

        // 2. 3-Stage Signal Path Card (Source → Playback Device → Connected Device)
        signalCard = new SignalPathContainer(activity);
        signalCard.setOrientation(LinearLayout.VERTICAL);
        signalCard.setBackground(flatRounded(SURFACE_CARD, STROKE_HAIRLINE, 16));
        signalCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        sourceNode = createNodeRow();
        signalCard.addView(sourceNode.row);

        conn12 = createConnectorGap();
        signalCard.addView(conn12.row);

        deviceNode = createNodeRow();
        signalCard.addView(deviceNode.row);

        conn23 = createConnectorGap();
        signalCard.addView(conn23.row);

        outputNode = createNodeRow();
        signalCard.addView(outputNode.row);

        signalCard.bindAnchors(sourceNode.iconContainer, deviceNode.iconContainer, outputNode.iconContainer);
        LinearLayout.LayoutParams signalParams = new LinearLayout.LayoutParams(-1, -2);
        signalParams.topMargin = dp(10);
        body.addView(signalCard, signalParams);

        LinearLayout pathsCard = new LinearLayout(activity);
        pathsCard.setOrientation(LinearLayout.VERTICAL);
        pathsCard.setBackground(flatRounded(SURFACE_CARD, STROKE_HAIRLINE, 16));
        pathsCard.setClipToOutline(true);

        LinearLayout optionsRow = new LinearLayout(activity);
        optionsRow.setGravity(Gravity.CENTER_VERTICAL);
        optionsRow.setMinimumHeight(dp(48));
        optionsRow.setPadding(dp(16), dp(10), dp(16), dp(10));
        optionsRow.setBackground(ripple(solidPill(SURFACE_CARD), 16));
        optionsRow.setFocusable(true);
        optionsRow.setOnClickListener(v -> toggleOutputOptions());
        outputOptionsButton = optionsRow;
        String optionsTitle = AudioQualityStrings.text(activity, "output_options");
        optionsRow.setContentDescription(optionsTitle);
        TextView optionsLabel = label(optionsTitle, 15, fontSemibold, TEXT_PRIMARY);
        optionsLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        optionsRow.addView(optionsLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        outputOptionsChevron = new ImageView(activity);
        outputOptionsChevron.setImageDrawable(AudioQualityIcons.create(
                AudioQualityIcons.Glyph.CHEVRON_RIGHT, TEXT_SECONDARY, dp(14)));
        outputOptionsChevron.setRotation(90f);
        optionsRow.addView(outputOptionsChevron, new LinearLayout.LayoutParams(dp(14), dp(14)));
        pathsCard.addView(optionsRow, new LinearLayout.LayoutParams(-1, -2));
        outputOptionsButton.setStateDescription(AudioQualityStrings.text(activity, "options_collapsed"));
        outputOptions = new LinearLayout(activity);
        outputOptions.setOrientation(LinearLayout.VERTICAL);
        outputOptions.setPadding(dp(16), 0, dp(16), dp(14));
        outputOptions.setVisibility(View.GONE);
        pathsCard.addView(outputOptions, new LinearLayout.LayoutParams(-1, -2));

        pathsListContainer = new LinearLayout(activity);
        pathsListContainer.setOrientation(LinearLayout.VERTICAL);

        usbDirectToggleRow = new LinearLayout(activity);
        usbDirectToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        usbDirectToggleRow.setGravity(Gravity.CENTER_VERTICAL);
        usbDirectToggleRow.setMinimumHeight(dp(48));
        usbDirectToggleRow.setClickable(true);
        usbDirectToggleRow.setFocusable(true);
        usbDirectToggleRow.setVisibility(View.GONE);

        LinearLayout usbDirectTextCol = new LinearLayout(activity);
        usbDirectTextCol.setOrientation(LinearLayout.VERTICAL);
        usbDirectToggleTitle = label(AudioQualityStrings.text(activity, "usb_direct_toggle_title"), 14, fontSemibold, TEXT_PRIMARY);
        usbDirectTextCol.addView(usbDirectToggleTitle);

        usbDirectToggleSubtitle = label("", 12.5f, fontRegular, TEXT_SECONDARY);
        usbDirectToggleSubtitle.setLineSpacing(dp(2), 1f);
        usbDirectToggleSubtitle.setPadding(0, dp(3), 0, 0);
        usbDirectTextCol.addView(usbDirectToggleSubtitle);

        LinearLayout.LayoutParams udTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        udTextParams.setMarginEnd(dp(12));
        usbDirectToggleRow.addView(usbDirectTextCol, udTextParams);

        usbDirectToggleSwitch = new AppleSwitchView(activity);
        usbDirectToggleRow.addView(usbDirectToggleSwitch, new LinearLayout.LayoutParams(dp(46), dp(28)));

        usbDirectToggleRow.setOnClickListener(v -> toggleUsbDirectDriver());
        outputOptions.addView(usbDirectToggleRow, new LinearLayout.LayoutParams(-1, -2));

        // Bit-perfect DAC hardware volume control row (shown while USB Direct Driver is streaming)
        hwVolDivider = new View(activity);
        hwVolDivider.setBackgroundColor(STROKE_HAIRLINE);
        LinearLayout.LayoutParams hvDivParams = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        hvDivParams.topMargin = dp(10);
        hvDivParams.bottomMargin = dp(10);
        hwVolDivider.setVisibility(View.GONE);
        outputSummary.addView(hwVolDivider, hvDivParams);

        hwVolContainer = new LinearLayout(activity);
        hwVolContainer.setOrientation(LinearLayout.VERTICAL);
        hwVolContainer.setVisibility(View.GONE);

        LinearLayout hwVolHeader = new LinearLayout(activity);
        hwVolHeader.setOrientation(LinearLayout.HORIZONTAL);
        hwVolHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView hwVolTitle = label(AudioQualityStrings.text(activity, "hw_vol_title"), 13.5f, fontSemibold, TEXT_PRIMARY);
        hwVolHeader.addView(hwVolTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        hwVolValue = label("", 12.5f, fontMedium, TEXT_SPEC);
        hwVolValue.setFontFeatureSettings("tnum");
        hwVolHeader.addView(hwVolValue, new LinearLayout.LayoutParams(-2, -2));
        hwVolContainer.addView(hwVolHeader, new LinearLayout.LayoutParams(-1, -2));

        hwVolSlider = new AppleVolumeSliderView(activity, new AppleVolumeSliderView.Listener() {
            @Override
            public void onTrackingChanged(boolean tracking) {
                draggingHwVolume = tracking;
                if (!tracking) {
                    sheet.postDelayed(AudioQualitySheet.this::refresh, 80L);
                }
            }

            @Override
            public void onValueChanged(float percent) {
                UsbDirectAudioEngine.setHardwareVolumePercent(activity, percent);
                if (percent <= 0.01f) {
                    hwVolValue.setText(AudioQualityStrings.text(activity, "hw_vol_muted"));
                } else {
                    int db256 = UsbAudioDescriptorParser.percentToVolumeDb256(
                            percent, currentHwVolMinDb256, currentHwVolMaxDb256, 1);
                    hwVolValue.setText(UsbAudioDescriptorParser.formatVolumeDb(db256));
                }
            }
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, dp(32));
        sliderLp.topMargin = dp(6);
        hwVolContainer.addView(hwVolSlider, sliderLp);

        hwVolSubtitle = label(AudioQualityStrings.text(activity, "hw_vol_unsupported"), 12, fontRegular, TEXT_SECONDARY);
        hwVolSubtitle.setLineSpacing(dp(2), 1f);
        hwVolSubtitle.setPadding(0, dp(2), 0, 0);
        hwVolContainer.addView(hwVolSubtitle, new LinearLayout.LayoutParams(-1, -2));

        outputSummary.addView(hwVolContainer, new LinearLayout.LayoutParams(-1, -2));

        usbToggleDivider = new View(activity);
        usbToggleDivider.setBackgroundColor(STROKE_HAIRLINE);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        divParams.topMargin = dp(10);
        divParams.bottomMargin = dp(10);
        usbToggleDivider.setVisibility(View.GONE);
        outputOptions.addView(usbToggleDivider, divParams);

        usbToggleRow = new LinearLayout(activity);
        usbToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        usbToggleRow.setGravity(Gravity.CENTER_VERTICAL);
        usbToggleRow.setMinimumHeight(dp(48));
        usbToggleRow.setClickable(true);
        usbToggleRow.setFocusable(true);
        usbToggleRow.setVisibility(View.GONE);

        LinearLayout usbToggleTextCol = new LinearLayout(activity);
        usbToggleTextCol.setOrientation(LinearLayout.VERTICAL);
        usbToggleTitle = label(AudioQualityStrings.text(activity, "usb_bp_toggle_title"), 14, fontSemibold, TEXT_PRIMARY);
        usbToggleTextCol.addView(usbToggleTitle);

        usbToggleSubtitle = label("", 12.5f, fontRegular, TEXT_SECONDARY);
        usbToggleSubtitle.setLineSpacing(dp(2), 1f);
        usbToggleSubtitle.setPadding(0, dp(3), 0, 0);
        usbToggleTextCol.addView(usbToggleSubtitle);

        LinearLayout.LayoutParams utTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        utTextParams.setMarginEnd(dp(12));
        usbToggleRow.addView(usbToggleTextCol, utTextParams);

        usbToggleSwitch = new AppleSwitchView(activity);
        usbToggleRow.addView(usbToggleSwitch, new LinearLayout.LayoutParams(dp(46), dp(28)));

        usbToggleRow.setOnClickListener(v -> toggleExclusiveUsbBitPerfect());
        outputOptions.addView(usbToggleRow, new LinearLayout.LayoutParams(-1, -2));

        TextView pathsHeader = label(AudioQualityStrings.text(activity, "paths_header"), 12, fontMedium, TEXT_SECONDARY);
        pathsHeader.setPadding(0, dp(14), 0, dp(8));
        outputOptions.addView(pathsHeader);
        outputOptions.addView(pathsListContainer, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams pathsCardParams = new LinearLayout.LayoutParams(-1, -2);
        pathsCardParams.topMargin = dp(10);
        body.addView(pathsCard, pathsCardParams);

        // 4. Optional Bluetooth codec permission row
        btPermCard = new LinearLayout(activity);
        btPermCard.setOrientation(LinearLayout.HORIZONTAL);
        btPermCard.setGravity(Gravity.CENTER_VERTICAL);
        btPermCard.setBackground(ripple(flatRounded(SURFACE_CARD, STROKE_HAIRLINE, 16), 16));
        btPermCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        btPermCard.setClickable(true);
        btPermCard.setFocusable(true);
        btPermCard.setVisibility(View.GONE);
        btPermCard.setOnClickListener(v -> requestBluetoothConnectPermission());

        LinearLayout btPermTextCol = new LinearLayout(activity);
        btPermTextCol.setOrientation(LinearLayout.VERTICAL);
        btPermTextCol.addView(label(AudioQualityStrings.text(activity, "bt_perm_title"), 13.5f, fontSemibold, TEXT_PRIMARY));
        TextView btPermDesc = label(AudioQualityStrings.text(activity, "bt_perm_desc"), 12, fontRegular, TEXT_SECONDARY);
        btPermDesc.setLineSpacing(dp(2), 1f);
        btPermDesc.setPadding(0, dp(3), 0, 0);
        btPermTextCol.addView(btPermDesc);

        LinearLayout.LayoutParams btTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        btTextParams.setMarginEnd(dp(12));
        btPermCard.addView(btPermTextCol, btTextParams);

        TextView btPermAction = label(AudioQualityStrings.text(activity, "bt_perm_allow"), 13.5f, fontSemibold, APPLE_MUSIC_RED);
        btPermCard.addView(btPermAction, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams btPermParams = new LinearLayout.LayoutParams(-1, -2);
        btPermParams.topMargin = dp(10);
        body.addView(btPermCard, btPermParams);

        // 5. Expandable Info Card (toggled by header info button)
        infoBox = new LinearLayout(activity);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setBackground(flatRounded(SURFACE_CARD, STROKE_HAIRLINE, 16));
        infoBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        infoBox.setVisibility(View.GONE);

        TextView infoTitle = label(AudioQualityStrings.text(activity, "info_title"), 13, fontSemibold, TEXT_PRIMARY);
        infoBox.addView(infoTitle);

        infoBody = label("", 12.5f, fontRegular, TEXT_SECONDARY);
        infoBody.setLineSpacing(dp(3), 1f);
        infoBody.setPadding(0, dp(5), 0, 0);
        infoBox.addView(infoBody);

        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(10);
        body.addView(infoBox, infoParams);

        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 6. Footer button: Jump to Apple Music Audio Quality Settings
        LinearLayout footerBtn = new LinearLayout(activity);
        footerBtn.setOrientation(LinearLayout.HORIZONTAL);
        footerBtn.setGravity(Gravity.CENTER_VERTICAL);
        footerBtn.setPadding(dp(16), 0, dp(14), 0);
        footerBtn.setBackground(ripple(flatRounded(SURFACE_ELEVATED, STROKE_HAIRLINE, 14), 14));
        footerBtn.setClickable(true);
        footerBtn.setFocusable(true);
        footerBtn.setOnClickListener(v -> {
            dismissAnimated();
            AudioQualityController.openAudioQualitySettings(activity);
        });

        ImageView settingsIcon = new ImageView(activity);
        settingsIcon.setImageDrawable(AudioQualityIcons.create(AudioQualityIcons.Glyph.SETTINGS, APPLE_MUSIC_RED, dp(18)));
        LinearLayout.LayoutParams siParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        siParams.setMarginEnd(dp(10));
        footerBtn.addView(settingsIcon, siParams);

        TextView settingsLabel = label(AudioQualityStrings.text(activity, "open_settings"), 15, fontSemibold, TEXT_PRIMARY);
        footerBtn.addView(settingsLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView chevron = new ImageView(activity);
        chevron.setImageDrawable(AudioQualityIcons.create(AudioQualityIcons.Glyph.CHEVRON_RIGHT, TEXT_TERTIARY, dp(15)));
        footerBtn.addView(chevron, new LinearLayout.LayoutParams(dp(15), dp(15)));

        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, dp(48));
        footerParams.setMargins(dp(20), dp(4), dp(20), dp(8));
        sheet.addView(footerBtn, footerParams);

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int maxSheetWidth = Math.min(dm.widthPixels, dp(520));
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
                dm.widthPixels > dp(560) ? maxSheetWidth : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        scrim.addView(sheet, sheetParams);
        dialog.setContentView(scrim);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dismissAnimated();
                return true;
            }
            return false;
        });

        dialog.setOnDismissListener(d -> {
            AudioQualityMonitor.removeLiveListener(liveRefresh);
            if (currentDialog.get() == d) {
                currentDialog = new WeakReference<>(null);
            }
        });
        dialog.setOnCancelListener(d -> AudioQualityMonitor.removeLiveListener(liveRefresh));
    }

    private void present() {
        refresh();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
            }
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        currentDialog = new WeakReference<>(dialog);
        AudioQualityMonitor.addLiveListener(activity, liveRefresh);
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        scrim.requestApplyInsets();

        scrim.setAlpha(0f);
        sheet.setTranslationY(dp(80));
        scrim.animate()
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        sheet.animate()
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start();
    }

    private void dismissAnimated() {
        if (dismissing) return;
        dismissing = true;
        AudioQualityMonitor.removeLiveListener(liveRefresh);
        float targetY = Math.max(sheet.getHeight(), dp(260));
        scrim.animate()
                .alpha(0f)
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        sheet.animate()
                .translationY(targetY)
                .setDuration(200L)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (dialog.isShowing()) {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                })
                .start();
    }

    private void toggleInfoBox() {
        infoExpanded = !infoExpanded;
        infoBox.setVisibility(infoExpanded ? View.VISIBLE : View.GONE);
        if (infoExpanded) {
            infoBox.post(() -> scroll.smoothScrollTo(0, infoBox.getTop()));
        }
    }

    private void toggleOutputOptions() {
        outputOptionsExpanded = !outputOptionsExpanded;
        outputOptions.setVisibility(outputOptionsExpanded ? View.VISIBLE : View.GONE);
        outputOptionsButton.setStateDescription(AudioQualityStrings.text(activity,
                outputOptionsExpanded ? "options_expanded" : "options_collapsed"));
        outputOptionsChevron.setRotation(outputOptionsExpanded ? -90f : 90f);
        if (outputOptionsExpanded) {
            refresh();
            View card = (View) outputOptions.getParent();
            card.post(() -> scroll.smoothScrollTo(0, card.getTop()));
        }
    }

    private void toggleUsbDirectDriver() {
        boolean currentlyEnabled = UsbDirectAudioEngine.isEnabled(activity);
        if (currentlyEnabled) {
            UsbDirectAudioEngine.setEnabled(activity, false);
            usbDirectToggleSwitch.setChecked(false);
            refresh();
            sheet.postDelayed(this::refresh, 380L);
            return;
        }
        UsbDevice usbDevice = UsbDirectAudioEngine.findConnectedUsbAudioDevice(activity);
        if (usbDevice == null) {
            refresh();
            return;
        }
        if (UsbDirectAudioEngine.hasUsbPermission(activity, usbDevice)) {
            UsbDirectAudioEngine.setEnabled(activity, true);
            usbDirectToggleSwitch.setChecked(true);
            refresh();
            sheet.postDelayed(this::refresh, 260L);
        } else {
            UsbDirectAudioEngine.requestUsbPermission(activity, usbDevice, granted -> {
                if (granted) {
                    UsbDirectAudioEngine.setEnabled(activity, true);
                    usbDirectToggleSwitch.setChecked(true);
                }
                refresh();
                sheet.postDelayed(this::refresh, 300L);
            });
        }
    }

    private void toggleExclusiveUsbBitPerfect() {
        boolean next = !AudioQualityBitPerfect.isExclusiveUsbEnabled(activity);
        if (next && UsbDirectAudioEngine.isEnabled(activity)) {
            UsbDirectAudioEngine.setEnabled(activity, false);
            usbDirectToggleSwitch.setChecked(false);
        }
        AudioQualityBitPerfect.setExclusiveUsbEnabled(activity, next);
        usbToggleSwitch.setChecked(next);
        refresh();
        // Refresh again briefly after ExoPlayer's playback thread reopens AudioTrack.
        sheet.postDelayed(this::refresh, 220L);
    }

    private void refresh() {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        View badgeView = badgeViewRef.get();
        if (badgeView != null && badgeView.isAttachedToWindow()) {
            String[] liveMeta = AudioQualityController.resolveTrackMetadata(badgeView, activity);
            if (!liveMeta[0].isEmpty() || !liveMeta[1].isEmpty()) {
                lastTrackTitle = liveMeta[0];
                lastTrackSubtitle = liveMeta[1];
            }
        }

        AudioQualityModels.ResolvedSignalPath data = AudioQualityMonitor.captureSnapshot(
                activity,
                uiVariant,
                uiBitDepth,
                uiSampleRate,
                uiCodec,
                uiCapabilities,
                lastTrackTitle,
                lastTrackSubtitle
        );

        String trackLine = buildTrackLine(data.trackTitle, data.trackSubtitle);
        trackSubtitle.setText(trackLine);
        trackSubtitle.setVisibility(trackLine.isEmpty() ? View.GONE : View.VISIBLE);
        renderCurrentOutput(data);

        // Stage 1: Source
        sourceNode.setIcon(AudioQualityIcons.Glyph.MUSIC_NOTE);
        sourceNode.overline.setText(joinDot(
                AudioQualityStrings.text(activity, "stage_source"),
                data.sourceCodecLabel
        ));
        sourceNode.title.setText(tierTitle(data.tier));
        sourceNode.spec.setText(formatSourceSpec(data));
        sourceNode.setDetail(buildSourceDetail(data));

        // Connector 1 -> 2
        if (data.resampledInDevice) {
            conn12.setNote(AudioQualityStrings.text(
                    activity,
                    "connector_resampled",
                    AudioQualityModels.formatSampleRateKHz(data.sourceSampleRateHz),
                    AudioQualityModels.formatSampleRateKHz(data.deviceSampleRateHz)
            ));
        } else {
            conn12.setNote("");
        }

        // Stage 2: Playback Device
        deviceNode.setIcon(AudioQualityIcons.Glyph.PHONE);
        deviceNode.overline.setText(AudioQualityStrings.text(activity, "stage_device"));
        deviceNode.title.setText(data.deviceName.isEmpty() ? "Android" : data.deviceName);
        deviceNode.spec.setText(formatDeviceSpec(data));
        deviceNode.setDetail(buildDeviceDetail(data));

        // Connector 2 -> 3
        conn23.setNote("");

        // Stage 3: Connected Output Device
        outputNode.setIcon(glyphForOutput(data.outputKind));
        outputNode.overline.setText(joinDot(
                AudioQualityStrings.text(activity, "stage_output"),
                outputTypeLabel(data.outputKind)
        ));
        outputNode.title.setText(resolveOutputDisplayName(data));
        outputNode.spec.setText(formatOutputSpec(data));
        outputNode.setDetail(buildOutputDetail(data));

        signalCard.invalidate();

        // Populate available vs active output paths
        if (outputOptionsExpanded) renderOutputPaths(data);

        // 1. Userspace USB Direct Driver toggle (shown whenever a USB Audio Class DAC is connected
        //    and libvoidhack_usb_audio.so is loaded — works even when OS USB HAL is unexposed!)
        boolean showUsbDirectToggle = data.usbDirect.nativeLoaded
                && (data.usbDirect.deviceAttached || data.usbDirect.settingEnabled);
        usbDirectToggleRow.setVisibility(showUsbDirectToggle ? View.VISIBLE : View.GONE);
        if (showUsbDirectToggle) {
            usbDirectToggleSwitch.setChecked(data.usbDirect.settingEnabled);
            usbDirectToggleTitle.setText(AudioQualityStrings.text(activity, "usb_direct_toggle_title"));
            if (!data.usbDirect.permissionGranted && !data.usbDirect.settingEnabled) {
                usbDirectToggleSubtitle.setText(AudioQualityStrings.text(activity, "usb_direct_toggle_perm_desc"));
            } else {
                usbDirectToggleSubtitle.setText(AudioQualityStrings.text(activity, "usb_direct_toggle_desc"));
            }
        }

        // 1b. Bit-perfect UAC Hardware Volume slider when USB Direct Driver is streaming
        boolean showHwVol = data.pipelineMode == AudioQualityModels.PipelineMode.USB_DIRECT_DRIVER;
        hwVolDivider.setVisibility(showHwVol ? View.VISIBLE : View.GONE);
        hwVolContainer.setVisibility(showHwVol ? View.VISIBLE : View.GONE);
        if (showHwVol) {
            if (data.usbDirect.hwVolumeSupported) {
                currentHwVolMinDb256 = data.usbDirect.hwVolumeMinDb256;
                currentHwVolMaxDb256 = data.usbDirect.hwVolumeMaxDb256;
                hwVolSlider.setVisibility(View.VISIBLE);
                hwVolValue.setVisibility(View.VISIBLE);
                hwVolSubtitle.setVisibility(View.GONE);
                if (!draggingHwVolume) {
                    hwVolSlider.setPercent(data.usbDirect.hwVolumeMuted ? 0f : data.usbDirect.hwVolumePercent);
                    hwVolValue.setText(data.usbDirect.hwVolumeMuted
                            ? AudioQualityStrings.text(activity, "hw_vol_muted")
                            : UsbAudioDescriptorParser.formatVolumeDb(data.usbDirect.hwVolumeDb256));
                }
            } else {
                hwVolSlider.setVisibility(View.GONE);
                hwVolValue.setVisibility(View.GONE);
                hwVolSubtitle.setVisibility(View.VISIBLE);
            }
        }

        // 2. Optional Android 14+ OS HAL Bit-Perfect / Preferred Mixer toggle when HAL advertises it
        boolean showUsbToggle = Build.VERSION.SDK_INT >= 34
                && data.outputKind == AudioQualityModels.OutputKind.USB
                && (data.usbBitPerfectSupportedByHal || data.usbPreferredMixerSupportedByHal);
        usbToggleDivider.setVisibility(showUsbToggle && showUsbDirectToggle ? View.VISIBLE : View.GONE);
        usbToggleRow.setVisibility(showUsbToggle ? View.VISIBLE : View.GONE);
        if (showUsbToggle) {
            usbToggleSwitch.setChecked(data.usbExclusiveSettingEnabled);
            if (data.usbBitPerfectSupportedByHal) {
                usbToggleTitle.setText(AudioQualityStrings.text(activity, "usb_bp_toggle_title"));
                String desc = AudioQualityStrings.text(activity, "usb_bp_toggle_desc");
                if (data.usbExclusiveSettingEnabled && data.soundCheckEnabled) {
                    desc = desc + " " + AudioQualityStrings.text(activity, "usb_bp_soundcheck_hint");
                }
                usbToggleSubtitle.setText(desc);
            } else {
                usbToggleTitle.setText(AudioQualityStrings.text(activity, "usb_pref_toggle_title"));
                usbToggleSubtitle.setText(AudioQualityStrings.text(activity, "usb_pref_toggle_desc"));
            }
        }

        boolean showBtPerm = data.outputKind == AudioQualityModels.OutputKind.BLUETOOTH
                && AudioQualityMonitor.canRequestBluetoothCodecPermission(activity);
        btPermCard.setVisibility(showBtPerm ? View.VISIBLE : View.GONE);

        infoBody.setText(tierDescription(data.tier) + "\n\n" + buildInfoExplanation(data));
    }

    private void renderCurrentOutput(AudioQualityModels.ResolvedSignalPath data) {
        AudioQualityModels.OutputPathId output = AudioQualityModels.OutputPathId.ANDROID_MIXER;
        for (AudioQualityModels.OutputPathEntry path : data.outputPaths) {
            if (path.availability != AudioQualityModels.PathAvailability.ACTIVE) continue;
            // With Bluetooth or Cast, the transport is the output and the mixer is an upstream stage.
            output = path.id;
        }

        boolean bitPerfect = data.integrity == AudioQualityModels.SignalIntegrity.BIT_PERFECT
                && (output == AudioQualityModels.OutputPathId.USB_DIRECT_DRIVER
                || (!data.soundCheckEnabled && !data.eqEnabled));
        String title = pathDisplayName(output);
        String description;
        switch (output) {
            case USB_DIRECT_DRIVER:
                description = bitPerfect ? "summary_bit_perfect_usb" : "summary_usb_direct";
                break;
            case USB_BIT_PERFECT:
                title = AudioQualityStrings.text(activity, "summary_usb_exclusive");
                description = bitPerfect ? "summary_bit_perfect_android" : "summary_usb_exclusive_desc";
                break;
            case USB_PREFERRED_MIXER:
                description = "summary_usb_mixer";
                break;
            case DIRECT_PLAYBACK:
                title = AudioQualityStrings.text(activity, "summary_direct_title");
                description = "summary_direct_desc";
                break;
            case DOLBY_PASSTHROUGH:
                description = "summary_passthrough";
                break;
            case BLUETOOTH_A2DP:
            case BLUETOOTH_LE:
                description = "summary_bluetooth";
                break;
            case CAST_REMOTE:
                description = "summary_cast";
                break;
            default:
                description = "summary_mixer";
                break;
        }
        currentOutputTitle.setText(bitPerfect ? AudioQualityStrings.text(activity, "summary_bit_perfect") : title);
        currentOutputDescription.setText(AudioQualityStrings.text(activity, description));
    }

    private void renderOutputPaths(AudioQualityModels.ResolvedSignalPath data) {
        pathsListContainer.removeAllViews();
        List<AudioQualityModels.OutputPathEntry> entries = data.outputPaths;
        for (int i = 0; i < entries.size(); i++) {
            AudioQualityModels.OutputPathEntry entry = entries.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, i == 0 ? 0 : dp(9), 0, 0);

            boolean isActive = entry.availability == AudioQualityModels.PathAvailability.ACTIVE;
            boolean isUnavailable = entry.availability == AudioQualityModels.PathAvailability.UNAVAILABLE;

            View dot = new View(activity);
            dot.setBackground(solidPill(isActive ? ACTIVE_DOT_GREEN : (isUnavailable ? 0xFF3A3A3C : 0xFF636366)));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dotLp.setMarginEnd(dp(10));
            row.addView(dot, dotLp);

            LinearLayout textCol = new LinearLayout(activity);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = label(
                    pathDisplayName(entry.id),
                    13.5f,
                    isActive ? fontSemibold : fontMedium,
                    isActive ? TEXT_PRIMARY : (isUnavailable ? TEXT_DISABLED : TEXT_SPEC)
            );
            textCol.addView(nameTv);

            String sub = pathSubtitle(entry, data);
            if (!sub.isEmpty()) {
                TextView subTv = label(
                        sub,
                        11.5f,
                        fontRegular,
                        isUnavailable ? TEXT_DISABLED : TEXT_TERTIARY
                );
                subTv.setFontFeatureSettings("tnum");
                subTv.setPadding(0, dp(2), 0, 0);
                textCol.addView(subTv);
            }

            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1f);
            textLp.setMarginEnd(dp(10));
            row.addView(textCol, textLp);

            TextView statusTv = label(
                    pathStatusLabel(entry, data),
                    12,
                    isActive ? fontSemibold : fontRegular,
                    isActive ? TEXT_PRIMARY : (isUnavailable ? TEXT_DISABLED : TEXT_SECONDARY)
            );
            row.addView(statusTv, new LinearLayout.LayoutParams(-2, -2));

            pathsListContainer.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private String pathDisplayName(AudioQualityModels.OutputPathId id) {
        switch (id) {
            case USB_DIRECT_DRIVER:
                return AudioQualityStrings.text(activity, "path_usb_direct");
            case USB_BIT_PERFECT:
                return AudioQualityStrings.text(activity, "path_usb_bit_perfect");
            case USB_PREFERRED_MIXER:
                return AudioQualityStrings.text(activity, "path_usb_preferred");
            case DIRECT_PLAYBACK:
                return AudioQualityStrings.text(activity, "path_direct_pcm");
            case DOLBY_PASSTHROUGH:
                return AudioQualityStrings.text(activity, "path_dolby_passthrough");
            case BLUETOOTH_A2DP:
                return AudioQualityStrings.text(activity, "path_bluetooth_a2dp");
            case BLUETOOTH_LE:
                return AudioQualityStrings.text(activity, "path_bluetooth_le");
            case CAST_REMOTE:
                return AudioQualityStrings.text(activity, "path_cast");
            default:
                return AudioQualityStrings.text(activity, "path_android_mixer");
        }
    }

    private String pathStatusLabel(
            AudioQualityModels.OutputPathEntry entry,
            AudioQualityModels.ResolvedSignalPath data) {
        if (entry.id == AudioQualityModels.OutputPathId.USB_DIRECT_DRIVER
                && !data.usbDirect.deviceAttached) {
            return AudioQualityStrings.text(activity, "path_state_no_usb");
        }
        if (entry.id == AudioQualityModels.OutputPathId.USB_BIT_PERFECT
                && data.outputKind != AudioQualityModels.OutputKind.USB) {
            return AudioQualityStrings.text(activity, "path_state_no_usb");
        }
        switch (entry.availability) {
            case ACTIVE:
                return AudioQualityStrings.text(activity, "path_state_active");
            case AVAILABLE:
                return AudioQualityStrings.text(activity, "path_state_available");
            case PERMISSION_REQUIRED:
                return AudioQualityStrings.text(activity, "path_state_permission");
            case STANDBY:
                return AudioQualityStrings.text(activity, "path_state_standby");
            default:
                return AudioQualityStrings.text(activity, "path_state_unavailable");
        }
    }

    private String pathSubtitle(
            AudioQualityModels.OutputPathEntry entry,
            AudioQualityModels.ResolvedSignalPath data) {
        switch (entry.id) {
            case USB_DIRECT_DRIVER: {
                AudioQualityModels.UsbDirectState d = data.usbDirect;
                if (entry.availability == AudioQualityModels.PathAvailability.ACTIVE) {
                    int uac = d.uacVersion > 0 ? d.uacVersion : 2;
                    String rate = AudioQualityModels.formatSampleRateKHz(d.sampleRateHz);
                    return AudioQualityStrings.text(activity, "path_sub_usb_direct_active", uac, d.bitDepth, rate);
                }
                if (!d.deviceAttached) {
                    return AudioQualityStrings.text(activity, "path_sub_usb_direct_no_usb");
                }
                if (d.settingEnabled && UsbDirectAudioEngine.ERR_RATE_UNSUPPORTED.equals(d.errorReason)) {
                    String badRate = AudioQualityModels.formatSampleRateKHz(d.errorRateHz);
                    return AudioQualityStrings.text(activity, "path_sub_usb_direct_rate_fallback", badRate);
                }
                if (d.settingEnabled && UsbDirectAudioEngine.ERR_CHANNEL_UNSUPPORTED.equals(d.errorReason)) {
                    return AudioQualityStrings.text(activity, "path_sub_usb_direct_ch_fallback");
                }
                if (d.settingEnabled && UsbDirectAudioEngine.ERR_OPEN_FAILED.equals(d.errorReason)) {
                    return AudioQualityStrings.text(activity, "path_sub_usb_direct_open_failed");
                }
                if (entry.availability == AudioQualityModels.PathAvailability.PERMISSION_REQUIRED) {
                    return AudioQualityStrings.text(activity, "path_sub_usb_direct_perm");
                }
                return AudioQualityStrings.text(activity, "path_sub_usb_direct_ready");
            }
            case USB_BIT_PERFECT: {
                if (Build.VERSION.SDK_INT < 34) {
                    return AudioQualityStrings.text(activity, "path_sub_need_android14");
                }
                if (data.outputKind != AudioQualityModels.OutputKind.USB) {
                    return AudioQualityStrings.text(activity, "path_sub_usb_required");
                }
                if (entry.availability == AudioQualityModels.PathAvailability.ACTIVE) {
                    String rate = AudioQualityModels.formatSampleRateKHz(entry.activeRateHz);
                    String enc = AudioQualityModels.pcmEncodingShortLabel(entry.activeEncoding);
                    return AudioQualityStrings.text(activity, "path_sub_bp_active", enc, rate);
                }
                if (entry.availability == AudioQualityModels.PathAvailability.AVAILABLE) {
                    String maxRate = AudioQualityModels.formatSampleRateKHz(entry.maxRateHz);
                    if (entry.maxBits > 0 && !maxRate.isEmpty()) {
                        return AudioQualityStrings.text(activity, "cap_max_bits", entry.maxBits, maxRate);
                    }
                    return AudioQualityStrings.text(activity, "path_sub_bp_ready");
                }
                return AudioQualityStrings.text(activity, "path_sub_bp_hal_unsupported");
            }
            case USB_PREFERRED_MIXER: {
                if (entry.availability == AudioQualityModels.PathAvailability.ACTIVE) {
                    String rate = AudioQualityModels.formatSampleRateKHz(entry.activeRateHz);
                    String enc = AudioQualityModels.pcmEncodingShortLabel(entry.activeEncoding);
                    return enc + (rate.isEmpty() ? "" : " · " + rate);
                }
                String maxRate = AudioQualityModels.formatSampleRateKHz(entry.maxRateHz);
                if (entry.maxBits > 0 && !maxRate.isEmpty()) {
                    return AudioQualityStrings.text(activity, "cap_max_bits", entry.maxBits, maxRate);
                }
                return AudioQualityStrings.text(activity, "path_sub_pref_ready");
            }
            case DIRECT_PLAYBACK: {
                if (entry.availability == AudioQualityModels.PathAvailability.ACTIVE) {
                    String rate = AudioQualityModels.formatSampleRateKHz(entry.activeRateHz);
                    String enc = AudioQualityModels.pcmEncodingShortLabel(entry.activeEncoding);
                    return AudioQualityStrings.text(activity, "path_sub_direct_active", rate, enc);
                }
                if (entry.availability == AudioQualityModels.PathAvailability.AVAILABLE) {
                    return AudioQualityStrings.text(activity, "path_sub_direct_available");
                }
                return AudioQualityStrings.text(activity, "path_sub_direct_unavailable");
            }
            case DOLBY_PASSTHROUGH: {
                if (entry.availability == AudioQualityModels.PathAvailability.ACTIVE) {
                    return AudioQualityStrings.text(activity, "path_sub_passthrough_active", data.sourceCodecLabel);
                }
                return AudioQualityStrings.text(activity, "cap_atmos_hw");
            }
            case ANDROID_MIXER: {
                String rate = AudioQualityModels.formatSampleRateKHz(
                        entry.activeRateHz > 0 ? entry.activeRateHz : entry.maxRateHz);
                if (!rate.isEmpty()) {
                    return AudioQualityStrings.text(activity, "path_sub_mixer_rate", rate);
                }
                return AudioQualityStrings.text(activity, "path_sub_mixer_generic");
            }
            case BLUETOOTH_A2DP: {
                if (!data.bluetoothCodecName.isEmpty()) {
                    return data.bluetoothCodecName;
                }
                if (AudioQualityMonitor.hasBluetoothConnectPermission(activity)) {
                    return AudioQualityStrings.text(activity, "path_sub_bt_wait_reconnect");
                }
                return AudioQualityStrings.text(activity, "cap_bt_a2dp");
            }
            case BLUETOOTH_LE:
                return AudioQualityStrings.text(activity, "cap_bt_le");
            case CAST_REMOTE:
                return data.outputProductName;
            default:
                return "";
        }
    }

    private void requestBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < 31 || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            if (AudioQualityMonitor.hasBluetoothConnectPermission(activity)) {
                AudioQualityMonitor.ensureBluetoothCodecReceiverRegistered(activity);
                refresh();
                return;
            }
            if (btPermRequestedOnce
                    && !activity.shouldShowRequestPermissionRationale(AudioQualityMonitor.PERM_BLUETOOTH_CONNECT)) {
                Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                settingsIntent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                activity.startActivity(settingsIntent);
                return;
            }
            btPermRequestedOnce = true;
            activity.requestPermissions(
                    new String[]{AudioQualityMonitor.PERM_BLUETOOTH_CONNECT},
                    BT_PERM_REQ_CODE
            );
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Formatting & Labels
    // =========================================================================

    private String tierTitle(AudioQualityModels.AudioTier tier) {
        switch (tier) {
            case HI_RES_LOSSLESS:
                return AudioQualityStrings.text(activity, "tier_hires");
            case LOSSLESS:
                return AudioQualityStrings.text(activity, "tier_lossless");
            case DOLBY_ATMOS:
                return AudioQualityStrings.text(activity, "tier_atmos");
            case DOLBY_AUDIO:
                return AudioQualityStrings.text(activity, "tier_dolby_audio");
            case HIGH_QUALITY:
                return AudioQualityStrings.text(activity, "tier_hq");
            case HIGH_EFFICIENCY:
                return AudioQualityStrings.text(activity, "tier_he");
            default:
                return AudioQualityStrings.text(activity, "tier_unknown");
        }
    }

    private String tierDescription(AudioQualityModels.AudioTier tier) {
        switch (tier) {
            case HI_RES_LOSSLESS:
                return AudioQualityStrings.text(activity, "desc_hires");
            case LOSSLESS:
                return AudioQualityStrings.text(activity, "desc_lossless");
            case DOLBY_ATMOS:
                return AudioQualityStrings.text(activity, "desc_atmos");
            case DOLBY_AUDIO:
                return AudioQualityStrings.text(activity, "desc_dolby_audio");
            case HIGH_QUALITY:
                return AudioQualityStrings.text(activity, "desc_hq");
            case HIGH_EFFICIENCY:
                return AudioQualityStrings.text(activity, "desc_he");
            default:
                return AudioQualityStrings.text(activity, "desc_unknown");
        }
    }

    private String formatSourceSpec(AudioQualityModels.ResolvedSignalPath data) {
        String rate = AudioQualityModels.formatSampleRateKHz(data.sourceSampleRateHz);
        if (data.sourceBitDepth > 0 && !rate.isEmpty()) {
            return AudioQualityStrings.text(activity, "bit_format", data.sourceBitDepth, rate);
        }
        if (!rate.isEmpty()) {
            return !data.sourceCodecLabel.isEmpty() ? (rate + " · " + data.sourceCodecLabel) : rate;
        }
        return data.sourceCodecLabel;
    }

    private String buildSourceDetail(AudioQualityModels.ResolvedSignalPath data) {
        StringBuilder sb = new StringBuilder();
        if (data.tier == AudioQualityModels.AudioTier.DOLBY_ATMOS) {
            appendDot(sb, AudioQualityStrings.text(activity, "spatial_channels"));
        } else if (data.sourceChannelCount == 1) {
            appendDot(sb, AudioQualityStrings.text(activity, "mono"));
        } else if (data.sourceChannelCount == 2) {
            appendDot(sb, AudioQualityStrings.text(activity, "stereo"));
        } else if (data.sourceChannelCount > 2) {
            appendDot(sb, AudioQualityStrings.text(activity, "surround_channels", data.sourceChannelCount));
        }
        String kbps = AudioQualityModels.formatBitrateKbps(data.sourceBitrateBps);
        if (!kbps.isEmpty()) {
            appendDot(sb, kbps);
        }
        if (data.hiResAvailableInCatalog) {
            appendDot(sb, AudioQualityStrings.text(activity, "hires_available"));
        }
        return sb.toString();
    }

    private String formatDeviceSpec(AudioQualityModels.ResolvedSignalPath data) {
        String rate = AudioQualityModels.formatSampleRateKHz(data.deviceSampleRateHz);
        if (data.deviceBitstreamPassthrough) {
            return rate.isEmpty() ? "Dolby Bitstream" : (rate + " · " + data.sourceCodecLabel);
        }
        String pcmLabel = (data.pipelineMode == AudioQualityModels.PipelineMode.USB_DIRECT_DRIVER
                || data.pipelineMode == AudioQualityModels.PipelineMode.BIT_PERFECT
                || data.pipelineMode == AudioQualityModels.PipelineMode.USB_PREFERRED_MIXER)
                ? AudioQualityModels.pcmEncodingShortLabel(data.activeTrackEncoding)
                : "PCM";
        if (data.deviceBitDepth > 0 && !rate.isEmpty()) {
            return AudioQualityStrings.text(activity, "bit_format", data.deviceBitDepth, rate) + " · " + pcmLabel;
        }
        return !rate.isEmpty() ? (rate + " · " + pcmLabel) : pcmLabel;
    }

    private String buildDeviceDetail(AudioQualityModels.ResolvedSignalPath data) {
        StringBuilder sb = new StringBuilder();
        if (data.pipelineMode == AudioQualityModels.PipelineMode.USB_DIRECT_DRIVER) {
            int uac = data.usbDirect.uacVersion > 0 ? data.usbDirect.uacVersion : 2;
            String rateStr = AudioQualityModels.formatSampleRateKHz(data.deviceSampleRateHz);
            if (data.usbDirect.measuredRateHz > 0) {
                appendDot(sb, AudioQualityStrings.text(
                        activity, "device_detail_usb_direct_fb", uac, rateStr, data.usbDirect.measuredRateHz));
            } else {
                appendDot(sb, AudioQualityStrings.text(activity, "device_detail_usb_direct", uac, rateStr));
            }
            return sb.toString();
        }
        String trackRateStr = AudioQualityModels.formatSampleRateKHz(data.audioTrackSampleRateHz);
        String devRateStr = AudioQualityModels.formatSampleRateKHz(data.deviceSampleRateHz);
        if (!trackRateStr.isEmpty() && !devRateStr.isEmpty()) {
            if (!data.deviceBitstreamPassthrough && data.audioTrackSampleRateHz != data.deviceSampleRateHz) {
                appendDot(sb, AudioQualityStrings.text(activity, "device_detail_mixer", trackRateStr, devRateStr));
            } else {
                appendDot(sb, AudioQualityStrings.text(activity, "device_detail_track", trackRateStr));
            }
        }
        if (data.soundCheckEnabled) {
            appendDot(sb, AudioQualityStrings.text(activity, "device_detail_soundcheck"));
        }
        if (data.eqEnabled) {
            appendDot(sb, AudioQualityStrings.text(activity, "device_detail_eq"));
        }
        return sb.toString();
    }

    private String outputTypeLabel(AudioQualityModels.OutputKind kind) {
        switch (kind) {
            case USB:
                return AudioQualityStrings.text(activity, "out_type_usb");
            case BLUETOOTH:
                return AudioQualityStrings.text(activity, "out_type_bluetooth");
            case BLUETOOTH_LE:
                return AudioQualityStrings.text(activity, "out_type_ble");
            case WIRED_35MM:
                return AudioQualityStrings.text(activity, "out_type_wired");
            case HDMI:
                return AudioQualityStrings.text(activity, "out_type_hdmi");
            case HDMI_ARC:
                return AudioQualityStrings.text(activity, "out_type_hdmi_arc");
            case HDMI_EARC:
                return AudioQualityStrings.text(activity, "out_type_hdmi_earc");
            case OPTICAL:
                return AudioQualityStrings.text(activity, "out_type_optical");
            case SPEAKER:
                return AudioQualityStrings.text(activity, "out_type_speaker");
            case CAST:
                return AudioQualityStrings.text(activity, "out_type_cast");
            default:
                return AudioQualityStrings.text(activity, "out_type_other");
        }
    }

    private String resolveOutputDisplayName(AudioQualityModels.ResolvedSignalPath data) {
        boolean generic = AudioQualityModels.isGenericBuiltInProductName(data.outputProductName, Build.MODEL);
        if (!generic && data.outputKind != AudioQualityModels.OutputKind.SPEAKER) {
            return data.outputProductName;
        }
        switch (data.outputKind) {
            case USB:
                return AudioQualityStrings.text(activity, "out_name_usb");
            case BLUETOOTH:
            case BLUETOOTH_LE:
                return AudioQualityStrings.text(activity, "out_name_bluetooth");
            case WIRED_35MM:
                if (data.rawDeviceType == AudioQualityModels.DEV_LINE_ANALOG
                        || data.rawDeviceType == AudioQualityModels.DEV_AUX_LINE) {
                    return AudioQualityStrings.text(activity, "out_name_line");
                }
                return AudioQualityStrings.text(activity, "out_name_wired");
            case HDMI:
            case HDMI_ARC:
            case HDMI_EARC:
                return AudioQualityStrings.text(activity, "out_name_hdmi");
            case OPTICAL:
                return AudioQualityStrings.text(activity, "out_name_optical");
            case SPEAKER:
                if (data.rawDeviceType == AudioQualityModels.DEV_BUILTIN_EARPIECE) {
                    return AudioQualityStrings.text(activity, "out_name_earpiece");
                }
                return AudioQualityStrings.text(activity, "out_name_speaker");
            case CAST:
                return AudioQualityStrings.text(activity, "out_name_cast");
            default:
                return AudioQualityStrings.text(activity, "out_name_other");
        }
    }

    private String formatOutputSpec(AudioQualityModels.ResolvedSignalPath data) {
        String rate = AudioQualityModels.formatSampleRateKHz(data.outputSampleRateHz);
        if (data.deviceBitstreamPassthrough) {
            return rate.isEmpty() ? "Dolby Bitstream" : (rate + " · " + data.sourceCodecLabel);
        }
        if (data.outputBitDepth > 0 && !rate.isEmpty()) {
            return AudioQualityStrings.text(activity, "bit_format", data.outputBitDepth, rate);
        }
        return rate;
    }

    private String buildOutputDetail(AudioQualityModels.ResolvedSignalPath data) {
        StringBuilder sb = new StringBuilder();
        if (data.outputKind == AudioQualityModels.OutputKind.BLUETOOTH_LE) {
            appendDot(sb, AudioQualityStrings.text(activity, "cap_bt_le"));
        } else if (data.outputKind == AudioQualityModels.OutputKind.BLUETOOTH) {
            if (!data.bluetoothCodecName.isEmpty()) {
                appendDot(sb, data.bluetoothCodecName);
            } else {
                appendDot(sb, AudioQualityStrings.text(activity, "cap_bt_a2dp"));
            }
        }
        if (data.outputMaxSampleRateHz > 0) {
            String maxRate = AudioQualityModels.formatSampleRateKHz(data.outputMaxSampleRateHz);
            if (data.outputMaxBitDepth > 0) {
                appendDot(sb, AudioQualityStrings.text(activity, "cap_max_bits", data.outputMaxBitDepth, maxRate));
            } else {
                appendDot(sb, AudioQualityStrings.text(activity, "cap_max", maxRate));
            }
        }
        if (data.outputSupportsAtmos) {
            appendDot(sb, AudioQualityStrings.text(activity, "cap_atmos_hw"));
        }
        return sb.toString();
    }

    private String buildInfoExplanation(AudioQualityModels.ResolvedSignalPath data) {
        if (data.pipelineMode == AudioQualityModels.PipelineMode.USB_DIRECT_DRIVER) {
            int uac = data.usbDirect.uacVersion > 0 ? data.usbDirect.uacVersion : 2;
            return AudioQualityStrings.text(activity, "info_usb_direct", uac);
        }
        if (data.pipelineMode == AudioQualityModels.PipelineMode.BIT_PERFECT) {
            return AudioQualityStrings.text(activity, "info_bit_perfect");
        }
        if (data.tier == AudioQualityModels.AudioTier.DOLBY_ATMOS
                || data.tier == AudioQualityModels.AudioTier.DOLBY_AUDIO) {
            return AudioQualityStrings.text(activity, "info_atmos");
        }
        if (data.outputKind == AudioQualityModels.OutputKind.BLUETOOTH
                || data.outputKind == AudioQualityModels.OutputKind.BLUETOOTH_LE) {
            if (data.outputKind == AudioQualityModels.OutputKind.BLUETOOTH
                    && data.bluetoothCodecName.isEmpty()
                    && AudioQualityMonitor.hasBluetoothConnectPermission(activity)) {
                return AudioQualityStrings.text(activity, "info_bluetooth_pending");
            }
            return AudioQualityStrings.text(activity, "info_bluetooth");
        }
        if (data.integrity == AudioQualityModels.SignalIntegrity.RESAMPLED) {
            String rate = AudioQualityModels.formatSampleRateKHz(
                    data.outputSampleRateHz > 0 ? data.outputSampleRateHz : data.deviceSampleRateHz);
            return AudioQualityStrings.text(activity, "info_resampled", rate);
        }
        if (data.integrity == AudioQualityModels.SignalIntegrity.HI_RES_LOSSLESS
                || data.integrity == AudioQualityModels.SignalIntegrity.LOSSLESS) {
            return AudioQualityStrings.text(activity, "info_lossless");
        }
        return AudioQualityStrings.text(activity, "info_general");
    }

    private static AudioQualityIcons.Glyph glyphForOutput(AudioQualityModels.OutputKind kind) {
        switch (kind) {
            case USB:
                return AudioQualityIcons.Glyph.USB;
            case BLUETOOTH:
            case BLUETOOTH_LE:
                return AudioQualityIcons.Glyph.BLUETOOTH;
            case WIRED_35MM:
                return AudioQualityIcons.Glyph.HEADPHONES_35MM;
            case HDMI:
            case HDMI_ARC:
            case HDMI_EARC:
                return AudioQualityIcons.Glyph.HDMI;
            case OPTICAL:
                return AudioQualityIcons.Glyph.OPTICAL;
            case CAST:
                return AudioQualityIcons.Glyph.CAST;
            default:
                return AudioQualityIcons.Glyph.SPEAKER;
        }
    }

    // =========================================================================
    // View Building Blocks
    // =========================================================================

    private NodeViews createNodeRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconContainer = new FrameLayout(activity);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(SURFACE_ELEVATED);
        circle.setStroke(Math.max(1, dp(1)), STROKE_CIRCLE);
        iconContainer.setBackground(circle);

        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconContainer.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(iconContainer, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(0, -2, 1f);
        colParams.setMarginStart(dp(12));

        TextView overlineTv = label("", 11.5f, fontMedium, TEXT_TERTIARY);
        textCol.addView(overlineTv);

        TextView titleTv = label("", 15.5f, fontSemibold, TEXT_PRIMARY);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(titleTv);

        TextView specTv = label("", 13.5f, fontMedium, TEXT_SPEC);
        specTv.setFontFeatureSettings("tnum");
        specTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(specTv);

        TextView detailTv = label("", 12, fontRegular, TEXT_TERTIARY);
        detailTv.setFontFeatureSettings("tnum");
        detailTv.setPadding(0, dp(2), 0, 0);
        detailTv.setVisibility(View.GONE);
        textCol.addView(detailTv);

        row.addView(textCol, colParams);
        return new NodeViews(row, iconContainer, icon, overlineTv, titleTv, specTv, detailTv);
    }

    private ConnectorGap createConnectorGap() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View lineSpace = new View(activity);
        row.addView(lineSpace, new LinearLayout.LayoutParams(dp(34), dp(12)));

        TextView note = label("", 11.5f, fontRegular, TEXT_SECONDARY);
        note.setFontFeatureSettings("tnum");
        note.setVisibility(View.GONE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.setMarginStart(dp(12));
        row.addView(note, np);

        return new ConnectorGap(row, lineSpace, note);
    }

    private ImageButton circleActionButton(Drawable icon, String contentDesc, View.OnClickListener listener) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageDrawable(icon);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btn.setPadding(dp(7), dp(7), dp(7), dp(7));
        btn.setBackground(ripple(solidPill(SURFACE_ELEVATED), 16));
        btn.setContentDescription(contentDesc);
        btn.setOnClickListener(listener);
        return btn;
    }

    private TextView label(String text, float sp, Typeface tf, int color) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTypeface(tf);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private void installDragToDismiss(View handle) {
        final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        final float[] downY = {0f};
        final boolean[] dragging = {false};
        handle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - downY[0];
                    if (dy > touchSlop) dragging[0] = true;
                    if (dragging[0]) {
                        float clamped = Math.max(0f, dy);
                        sheet.setTranslationY(clamped);
                        float frac = Math.min(1f, clamped / Math.max(dp(240), sheet.getHeight() * 0.55f));
                        scrim.setAlpha(1f - 0.65f * frac);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        float dyUp = event.getRawY() - downY[0];
                        if (dyUp > dp(96)) {
                            dismissAnimated();
                        } else {
                            sheet.animate().translationY(0f).setDuration(200L)
                                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
                            scrim.animate().alpha(1f).setDuration(180L).start();
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private Drawable buildSheetBackground() {
        float r = dp(24);
        float[] radii = new float[]{r, r, r, r, 0f, 0f, 0f, 0f};

        GradientDrawable fill = new GradientDrawable();
        fill.setColor(SURFACE_SHEET);
        fill.setCornerRadii(radii);

        GradientDrawable border = new GradientDrawable();
        border.setCornerRadii(radii);
        border.setColor(Color.TRANSPARENT);
        border.setStroke(Math.max(1, dp(1)), STROKE_HAIRLINE);

        LayerDrawable layer = new LayerDrawable(new Drawable[]{fill, border});
        layer.setLayerInset(1, 0, 0, 0, -dp(2));
        return layer;
    }

    private GradientDrawable flatRounded(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, dp(1)), strokeColor);
        }
        return d;
    }

    private GradientDrawable solidPill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(999));
        d.setColor(color);
        return d;
    }

    private RippleDrawable ripple(Drawable content, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, mask);
    }

    private Drawable loadAppleDrawable(String name, int tintColor) {
        int resId = AudioQualityStrings.id(activity, "drawable", name);
        if (resId == 0) return null;
        try {
            Drawable d = activity.getDrawable(resId);
            if (d == null) return null;
            Drawable copy = d.mutate();
            copy.setTint(tintColor);
            return copy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Typeface resolveFont(String name, Typeface fallback) {
        int resId = AudioQualityStrings.id(activity, "font", name);
        if (resId == 0) return fallback;
        try {
            Typeface tf = activity.getResources().getFont(resId);
            return tf != null ? tf : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void appendDot(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(part);
    }

    private static String joinDot(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (!a.isEmpty() && !b.isEmpty()) return a + " · " + b;
        return !a.isEmpty() ? a : b;
    }

    private static String buildTrackLine(String title, String subtitle) {
        return joinDot(title, subtitle);
    }

    // =========================================================================
    // Inner View Holders, Clean Monochrome Signal Line, & Flat iOS Switch
    // =========================================================================

    private final class NodeViews {
        final LinearLayout row;
        final FrameLayout iconContainer;
        final ImageView icon;
        final TextView overline;
        final TextView title;
        final TextView spec;
        final TextView detail;

        NodeViews(
                LinearLayout row,
                FrameLayout iconContainer,
                ImageView icon,
                TextView overline,
                TextView title,
                TextView spec,
                TextView detail) {
            this.row = row;
            this.iconContainer = iconContainer;
            this.icon = icon;
            this.overline = overline;
            this.title = title;
            this.spec = spec;
            this.detail = detail;
        }

        void setIcon(AudioQualityIcons.Glyph glyph) {
            icon.setImageDrawable(AudioQualityIcons.create(glyph, TEXT_PRIMARY, dp(19)));
        }

        void setDetail(String text) {
            if (text != null && !text.isEmpty()) {
                detail.setText(text);
                detail.setVisibility(View.VISIBLE);
            } else {
                detail.setVisibility(View.GONE);
            }
        }
    }

    private final class ConnectorGap {
        final LinearLayout row;
        final View lineSpace;
        final TextView note;

        ConnectorGap(LinearLayout row, View lineSpace, TextView note) {
            this.row = row;
            this.lineSpace = lineSpace;
            this.note = note;
        }

        void setNote(String text) {
            boolean hasNote = text != null && !text.isEmpty();
            note.setText(hasNote ? text : "");
            note.setVisibility(hasNote ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams lp = lineSpace.getLayoutParams();
            int targetHeight = dp(hasNote ? 24 : 12);
            if (lp != null && lp.height != targetHeight) {
                lp.height = targetHeight;
                lineSpace.setLayoutParams(lp);
            }
        }
    }

    private static final class AppleSwitchView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        AppleSwitchView(Context context) {
            super(context);
            trackPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setColor(0xFFFFFFFF);
        }

        void setChecked(boolean checked) {
            if (this.checked != checked) {
                this.checked = checked;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h * 0.5f;
            trackPaint.setColor(checked ? ACTIVE_DOT_GREEN : 0xFF3A3A3C);
            canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint);

            float pad = h * 0.08f;
            float thumbRadius = r - pad;
            float cx = checked ? (w - r) : r;
            canvas.drawCircle(cx, r, thumbRadius, thumbPaint);
        }
    }

    private static final class AppleVolumeSliderView extends View {
        interface Listener {
            void onTrackingChanged(boolean tracking);

            void onValueChanged(float percent);
        }

        private final Listener listener;
        private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private float percent = 50f;

        AppleVolumeSliderView(Context context, Listener listener) {
            super(context);
            this.listener = listener;
            this.density = context.getResources().getDisplayMetrics().density;
            trackBgPaint.setStyle(Paint.Style.FILL);
            trackBgPaint.setColor(0xFF3A3A3C);
            trackFillPaint.setStyle(Paint.Style.FILL);
            trackFillPaint.setColor(0xFFFFFFFF);
            thumbPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setColor(0xFFFFFFFF);
            thumbStrokePaint.setStyle(Paint.Style.STROKE);
            thumbStrokePaint.setStrokeWidth(1f * density);
            thumbStrokePaint.setColor(0x33000000);
        }

        void setPercent(float value) {
            float clamped = Math.max(0f, Math.min(100f, value));
            if (Math.abs(this.percent - clamped) > 0.05f) {
                this.percent = clamped;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float thumbR = 9.5f * density;
            float trackH = 5f * density;
            float trackR = trackH * 0.5f;
            float left = thumbR;
            float right = Math.max(left + 1f, w - thumbR);
            float cy = h * 0.5f;

            canvas.drawRoundRect(left, cy - trackR, right, cy + trackR, trackR, trackR, trackBgPaint);
            float thumbX = left + (right - left) * (percent / 100f);
            if (thumbX > left) {
                canvas.drawRoundRect(left, cy - trackR, thumbX, cy + trackR, trackR, trackR, trackFillPaint);
            }
            canvas.drawCircle(thumbX, cy, thumbR, thumbPaint);
            canvas.drawCircle(thumbX, cy, thumbR, thumbStrokePaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float thumbR = 9.5f * density;
            float left = thumbR;
            float right = Math.max(left + 1f, getWidth() - thumbR);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (listener != null) listener.onTrackingChanged(true);
                    updateFromX(event.getX(), left, right);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFromX(event.getX(), left, right);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    updateFromX(event.getX(), left, right);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (listener != null) listener.onTrackingChanged(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updateFromX(float x, float left, float right) {
            float frac = (x - left) / (right - left);
            float next = Math.max(0f, Math.min(100f, frac * 100f));
            if (Math.abs(next - percent) >= 0.25f || next == 0f || next == 100f) {
                percent = next;
                invalidate();
                if (listener != null) {
                    listener.onValueChanged(percent);
                }
            }
        }
    }

    private static final class SignalPathContainer extends LinearLayout {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private View anchor1;
        private View anchor2;
        private View anchor3;

        SignalPathContainer(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.BUTT);
            linePaint.setStrokeWidth(1.5f * density);
            linePaint.setColor(CONNECTOR_LINE);
        }

        void bindAnchors(View a1, View a2, View a3) {
            this.anchor1 = a1;
            this.anchor2 = a2;
            this.anchor3 = a3;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            drawSegment(canvas, anchor1, anchor2);
            drawSegment(canvas, anchor2, anchor3);
            super.dispatchDraw(canvas);
        }

        private void drawSegment(Canvas canvas, View topAnchor, View bottomAnchor) {
            if (topAnchor == null || bottomAnchor == null) return;
            float[] topPos = relativeCenterAndBounds(topAnchor);
            float[] botPos = relativeCenterAndBounds(bottomAnchor);
            float x = topPos[0];
            float yStart = topPos[2];
            float yEnd = botPos[1];
            if (yEnd <= yStart) return;
            canvas.drawLine(x, yStart, x, yEnd, linePaint);
        }

        private float[] relativeCenterAndBounds(View child) {
            float x = child.getLeft() + child.getWidth() * 0.5f;
            float top = child.getTop();
            View p = (View) child.getParent();
            while (p != null && p != this) {
                x += p.getLeft();
                top += p.getTop();
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
            return new float[]{x, top, top + child.getHeight()};
        }
    }
}
