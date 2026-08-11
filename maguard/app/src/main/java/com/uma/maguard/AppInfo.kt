package com.uma.maguard

/**
 * 端末にインストールされている1つのアプリを表すシンプルなデータクラス。
 * packageName: "com.instagram.android" のような一意のID
 * label: "Instagram" のような画面表示用の名前
 */
data class AppInfo(
    val packageName: String,
    val label: String
)
