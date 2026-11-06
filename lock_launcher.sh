#!/system/bin/sh
for p in com.android.launcher2 com.android.launcher3 com.android.launcher4 com.android.launcher5 com.android.launcher8 com.android.launcher11 com.android.launcher20 com.android.launcher24 com.android.launcher27 com.android.launcher30 com.android.launcher43 com.android.launcher44 com.android.launcher47 com.android.launcher51 com.sprd.powersavemodelauncher; do
    pm disable-user --user 0 "$p" 2>/dev/null
    pm clear-preferred-activities "$p" 2>/dev/null
done
cmd package set-home-activity com.x.carlauncher.debug/com.x.carlauncher.MainActivity
cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN

