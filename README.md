# AppUpdateSdk

A lightweight Android SDK for integrating in-app update functionality into Android applications.

## Installation

Add JitPack to your project repositories.

### settings.gradle.kts

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven(url = "[https://jitpack.io](https://jitpack.io)")
    }
}
```

Add the SDK dependency in your app module.

### app/build.gradle.kts

```kotlin
dependencies {
    implementation("com.github.RAHUL-Android-Developer:AppUpdateSdk:v1.0.1")
}
```

## Usage

Add your SDK initialization and update-check logic in an `Activity` or `Fragment`.

```kotlin
// Example:
// Initialize AppUpdateSdk here.
// Then call the SDK method that checks for an available update.
```

## Requirements

- Android Studio
- JDK 17
- Android project using Gradle Kotlin DSL or Gradle Groovy DSL
- JitPack repository configured in the consuming project

## Current version

```text
v1.0.1
```

## Release notes

### v1.0.1

- Initial public release
- Published with GitHub and JitPack
- Available as an Android dependency after the JitPack build succeeds

## Author

RAHUL KUMAR  
GitHub: [RAHUL-Android-Developer](https://github.com/RAHUL-Android-Developer)

## License

Add a license before distributing the SDK publicly. The MIT License or Apache License 2.0 are common choices for Android libraries.
