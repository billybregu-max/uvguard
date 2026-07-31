plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uvguard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uvguard.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Widget (Jetpack Glance)
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Compose (écran principal / réglages phototype)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Rafraîchissement périodique
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Appel réseau vers Open-Meteo
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Localisation
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Nécessaire pour .await() sur les Task Google Play Services (ex. client.lastLocation.await()
    // dans UvRepository.kt) — sans cette dépendance, la compilation échoue avec
    // "Unresolved reference: await".
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Stockage local simple (dose cumulée du jour, préférences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Sélection d'un point sur une carte (OpenStreetMap, gratuit, sans clé API)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Navigation entre les onglets (Réglages, Aujourd'hui, Suivi, Historique)
    implementation("androidx.navigation:navigation-compose:2.7.7")
}
