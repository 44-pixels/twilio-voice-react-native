// FORK — KAR-492
// Expo config plugin for native-first Twilio Voice FCM handling in prebuilt apps.

const fs = require('fs');
const path = require('path');
const {
  AndroidConfig,
  IOSConfig,
  createRunOncePlugin,
  withAndroidManifest,
  withDangerousMod,
  withXcodeProject,
} = require('@expo/config-plugins');

const pkg = require('./package.json');

const GENERATED_SERVICE_NAME = 'TwilioVoiceFirebaseMessagingService';
const RN_FIREBASE_SERVICE =
  'io.invertase.firebase.messaging.ReactNativeFirebaseMessagingService';
const TWILIO_SERVICE_ENABLED =
  'twiliovoicereactnative_firebasemessagingservice_enabled';
const MESSAGING_EVENT = 'com.google.firebase.MESSAGING_EVENT';
const USE_FULL_SCREEN_INTENT = 'android.permission.USE_FULL_SCREEN_INTENT';
const MANAGE_OWN_CALLS = 'android.permission.MANAGE_OWN_CALLS';
const FOREGROUND_SERVICE_PHONE_CALL =
  'android.permission.FOREGROUND_SERVICE_PHONE_CALL';
const INCOMING_CALL_ACTIVITY = 'com.twiliovoicereactnative.ForkIncomingCallActivity';
const CALLBACK_ACTIVITY = 'com.twiliovoicereactnative.ForkCallBackActivity';
const CALLBACK_ACTION = 'android.telecom.action.CALL_BACK';
const CALLBACK_DEEPLINK_META_DATA =
  'com.twiliovoicereactnative.CALL_BACK_DEEPLINK_BASE_URL';
// >>> FORK KAR-787 — see ForkCallSounds
const CALL_SOUND_PREFIX = 'twilio_voice_call_sound_';
const CALL_ENDED_PREFIX = 'twilio_voice_call_ended';
const CALL_SOUND_CATALOG = 'twilio_voice_call_sounds.json';
const IOS_CALL_SOUND_DIRECTORY = 'TwilioVoiceCallSounds';
// <<< FORK

// >>> FORK KAR-787 — see ForkCallSounds
function soundSource(projectRoot, sound, description) {
  if (!sound || typeof sound.source !== 'string' || sound.source.length === 0) {
    throw new Error(`${description} requires a source`);
  }
  const source = path.resolve(projectRoot, sound.source);
  if (!fs.existsSync(source) || !fs.statSync(source).isFile()) {
    throw new Error(`${description} source does not exist: ${sound.source}`);
  }
  const extension = path.extname(source).toLowerCase();
  if (!extension) throw new Error(`${description} source requires a file extension`);
  return {source, extension};
}

function callSounds(projectRoot, props) {
  const configuration = props && props.callSounds ? props.callSounds : {};
  const configuredRingtones = Array.isArray(configuration.ringtones)
    ? configuration.ringtones
    : [];
  const ids = new Set();
  let defaultRingtoneId = null;
  const ringtones = configuredRingtones.map(sound => {
    if (!sound || typeof sound.id !== 'string' || !/^[a-z][a-z0-9_]*$/.test(sound.id)) {
      throw new Error('Each ringtone id must match /^[a-z][a-z0-9_]*$/');
    }
    if (ids.has(sound.id)) throw new Error(`Duplicate ringtone id "${sound.id}"`);
    if (typeof sound.displayName !== 'string' || sound.displayName.length === 0) {
      throw new Error(`Ringtone "${sound.id}" requires a displayName`);
    }
    if (sound.default !== undefined && typeof sound.default !== 'boolean') {
      throw new Error(`Ringtone "${sound.id}" default must be a boolean`);
    }
    if (sound.default) {
      if (defaultRingtoneId) {
        throw new Error(
          `Only one ringtone can be default: "${defaultRingtoneId}" and "${sound.id}"`
        );
      }
      defaultRingtoneId = sound.id;
    }
    const {source, extension} = soundSource(
      projectRoot,
      sound,
      `Ringtone "${sound.id}"`
    );
    ids.add(sound.id);
    return {
      id: sound.id,
      displayName: sound.displayName,
      isDefault: sound.default === true,
      source,
      fileName: `${CALL_SOUND_PREFIX}${sound.id}${extension}`,
    };
  });

  let callEnded = null;
  if (configuration.callEnded) {
    const {source, extension} = soundSource(
      projectRoot,
      configuration.callEnded,
      'Call-ended sound'
    );
    callEnded = {
      source,
      fileName: `${CALL_ENDED_PREFIX}${extension}`,
    };
  }
  return {ringtones, callEnded};
}

function writeCallSoundCatalog(destination, sounds) {
  const ringtones = sounds.ringtones.map(
    ({id, displayName, isDefault, fileName}) => ({
      id,
      displayName,
      isDefault,
      fileName,
    })
  );
  const callEnded = sounds.callEnded
    ? {fileName: sounds.callEnded.fileName}
    : null;
  fs.writeFileSync(
    destination,
    `${JSON.stringify({ringtones, callEnded}, null, 2)}\n`
  );
}

function replaceGeneratedCallSounds(directory, sounds, removeStaleSounds = true) {
  fs.mkdirSync(directory, {recursive: true});
  for (const fileName of fs.readdirSync(directory)) {
    const isGeneratedSound = fileName.startsWith(CALL_SOUND_PREFIX)
      || fileName.startsWith(CALL_ENDED_PREFIX);
    if ((removeStaleSounds && isGeneratedSound) || fileName === CALL_SOUND_CATALOG) {
      fs.rmSync(path.join(directory, fileName));
    }
  }
  const files = sounds.callEnded
    ? [...sounds.ringtones, sounds.callEnded]
    : sounds.ringtones;
  for (const sound of files) {
    fs.copyFileSync(sound.source, path.join(directory, sound.fileName));
  }
  writeCallSoundCatalog(path.join(directory, CALL_SOUND_CATALOG), sounds);
}
// <<< FORK

function javaPackagePath(packageName) {
  return packageName.split('.').join(path.sep);
}

function generatedServiceSource(packageName) {
  return `package ${packageName};

import com.google.firebase.messaging.RemoteMessage;
import com.twiliovoicereactnative.NativeFirebaseMessageHandler;

/** Generated by @twilio/voice-react-native-sdk. */
public final class ${GENERATED_SERVICE_NAME}
    extends io.invertase.firebase.messaging.ReactNativeFirebaseMessagingService {
  @Override
  public void onMessageReceived(RemoteMessage message) {
    if (NativeFirebaseMessageHandler.handle(getApplicationContext(), message)) return;
    super.onMessageReceived(message);
  }
}
`;
}

function ensureToolsNamespace(manifest) {
  manifest.manifest.$['xmlns:tools'] = 'http://schemas.android.com/tools';
}

function ensureUsesPermission(manifest, permissionName) {
  manifest.manifest['uses-permission'] = manifest.manifest['uses-permission'] || [];
  const permissions = manifest.manifest['uses-permission'];
  if (
    permissions.some(
      permission => permission.$ && permission.$['android:name'] === permissionName
    )
  ) {
    return;
  }
  permissions.push({$: {'android:name': permissionName}});
}

function ensureMessagingEventService(application, serviceName, attributes) {
  application.service = application.service || [];

  const existing = application.service.find(
    service => service.$ && service.$['android:name'] === serviceName
  );
  if (existing) {
    existing.$ = {...existing.$, ...attributes};
    existing['intent-filter'] = existing['intent-filter'] || [];
    if (!hasMessagingEventFilter(existing)) {
      existing['intent-filter'].push(messagingEventFilter());
    }
    return;
  }

  application.service.push({
    $: {'android:name': serviceName, ...attributes},
    'intent-filter': [messagingEventFilter()],
  });
}

function ensureActivity(application, activityName, attributes, actions = []) {
  application.activity = application.activity || [];

  const existing = application.activity.find(
    activity => activity.$ && activity.$['android:name'] === activityName
  );
  const activity = existing || {$: {'android:name': activityName}};
  activity.$ = {...activity.$, ...attributes};
  if (actions.length > 0) {
    activity['intent-filter'] = [
      {action: actions.map(action => ({$: {'android:name': action}}))},
    ];
  }
  if (!existing) application.activity.push(activity);
}

function ensureMetaData(application, name, value) {
  if (!value) return;
  application['meta-data'] = application['meta-data'] || [];

  const existing = application['meta-data'].find(
    metaData => metaData.$ && metaData.$['android:name'] === name
  );
  if (existing) {
    existing.$ = {...existing.$, 'android:value': value};
    return;
  }

  application['meta-data'].push({$: {'android:name': name, 'android:value': value}});
}

function firstScheme(config) {
  const scheme = config.scheme;
  if (typeof scheme === 'string') return scheme;
  if (Array.isArray(scheme)) {
    return scheme.find(value => typeof value === 'string' && value.length > 0);
  }
  return null;
}

function callbackDeepLinkBaseUrl(config, props) {
  if (props && typeof props.androidCallbackDeeplinkBaseUrl === 'string') {
    return props.androidCallbackDeeplinkBaseUrl;
  }

  const scheme = firstScheme(config);
  return scheme ? `${scheme}://twilio-voice` : null;
}

function ensureRemovedService(application, serviceName) {
  application.service = application.service || [];

  const existing = application.service.find(
    service => service.$ && service.$['android:name'] === serviceName
  );
  if (existing) {
    existing.$ = {...existing.$, 'tools:node': 'remove'};
    delete existing['intent-filter'];
    return;
  }

  application.service.push({
    $: {
      'android:name': serviceName,
      'tools:node': 'remove',
    },
  });
}

function hasMessagingEventFilter(service) {
  return (service['intent-filter'] || []).some(filter =>
    (filter.action || []).some(
      action => action.$ && action.$['android:name'] === MESSAGING_EVENT
    )
  );
}

function messagingEventFilter() {
  return {
    action: [{$: {'android:name': MESSAGING_EVENT}}],
  };
}

function withTwilioVoiceFirebaseMessaging(config, props = {}) {
  config = withAndroidManifest(config, configWithManifest => {
    ensureToolsNamespace(configWithManifest.modResults);
    ensureUsesPermission(configWithManifest.modResults, USE_FULL_SCREEN_INTENT);
    ensureUsesPermission(configWithManifest.modResults, MANAGE_OWN_CALLS);
    ensureUsesPermission(configWithManifest.modResults, FOREGROUND_SERVICE_PHONE_CALL);

    const application = AndroidConfig.Manifest.getMainApplicationOrThrow(
      configWithManifest.modResults
    );

    ensureRemovedService(application, RN_FIREBASE_SERVICE);
    ensureMessagingEventService(application, `.${GENERATED_SERVICE_NAME}`, {
      'android:exported': 'false',
    });
    ensureActivity(application, INCOMING_CALL_ACTIVITY, {
      'android:showWhenLocked': 'true',
      'android:turnScreenOn': 'true',
      'android:launchMode': 'singleInstance',
      'android:excludeFromRecents': 'true',
      'android:noHistory': 'true',
      'android:theme': '@android:style/Theme.DeviceDefault.NoActionBar',
    });
    ensureActivity(
      application,
      CALLBACK_ACTIVITY,
      {'android:exported': 'true'},
      [CALLBACK_ACTION]
    );
    ensureMetaData(
      application,
      CALLBACK_DEEPLINK_META_DATA,
      callbackDeepLinkBaseUrl(config, props)
    );

    return configWithManifest;
  });

  config = withDangerousMod(config, [
    'android',
    configWithDangerousMod => {
      const packageName =
        configWithDangerousMod.android && configWithDangerousMod.android.package;

      if (!packageName) {
        throw new Error(
          'android.package is required to generate Twilio Voice Firebase service'
        );
      }

      const projectRoot = configWithDangerousMod.modRequest.platformProjectRoot;
      const sourceDir = path.join(
        projectRoot,
        'app',
        'src',
        'main',
        'java',
        javaPackagePath(packageName)
      );
      fs.mkdirSync(sourceDir, {recursive: true});
      fs.writeFileSync(
        path.join(sourceDir, `${GENERATED_SERVICE_NAME}.java`),
        generatedServiceSource(packageName)
      );

      // >>> FORK KAR-787 — see ForkCallSounds
      const sounds = callSounds(configWithDangerousMod.modRequest.projectRoot, props);
      replaceGeneratedCallSounds(
        path.join(projectRoot, 'app', 'src', 'main', 'res', 'raw'),
        sounds
      );
      // <<< FORK

      const valuesDir = path.join(
        projectRoot,
        'app',
        'src',
        'main',
        'res',
        'values'
      );
      fs.mkdirSync(valuesDir, {recursive: true});
      fs.writeFileSync(
        path.join(valuesDir, 'twilio_voice_react_native.xml'),
        `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n  <bool name="${TWILIO_SERVICE_ENABLED}">false</bool>\n</resources>\n`
      );

      return configWithDangerousMod;
    },
  ]);

  // >>> FORK KAR-787 — see ForkCallSounds
  config = withDangerousMod(config, [
    'ios',
    configWithDangerousMod => {
      const sounds = callSounds(configWithDangerousMod.modRequest.projectRoot, props);
      replaceGeneratedCallSounds(
        path.join(
          configWithDangerousMod.modRequest.platformProjectRoot,
          IOS_CALL_SOUND_DIRECTORY
        ),
        sounds,
        false
      );
      return configWithDangerousMod;
    },
  ]);

  config = withXcodeProject(config, configWithXcodeProject => {
    const sounds = callSounds(configWithXcodeProject.modRequest.projectRoot, props);
    const project = configWithXcodeProject.modResults;
    IOSConfig.XcodeUtils.ensureGroupRecursively(
      project,
      IOS_CALL_SOUND_DIRECTORY
    );
    const target = project.getFirstTarget().uuid;
    const callEndedResourceNames = sounds.callEnded
      ? [sounds.callEnded.fileName]
      : [];
    const resourceNames = [
      CALL_SOUND_CATALOG,
      ...sounds.ringtones.map(sound => sound.fileName),
      ...callEndedResourceNames,
    ];

    for (const resourceName of resourceNames) {
      IOSConfig.XcodeUtils.addResourceFileToGroup({
        filepath: `${IOS_CALL_SOUND_DIRECTORY}/${resourceName}`,
        groupName: IOS_CALL_SOUND_DIRECTORY,
        isBuildFile: true,
        project,
        targetUuid: target,
      });
    }
    return configWithXcodeProject;
  });
  // <<< FORK

  return config;
}

module.exports = createRunOncePlugin(
  withTwilioVoiceFirebaseMessaging,
  pkg.name,
  pkg.version
);
