require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "twilio-voice-react-native"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "11.0" }
  s.source       = { :git => "https://github.com/mhuynh5757/twilio-voice-react-native.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm}"
  # >>> FORK KAR-873 — see ForkCallbackRequestStore.h
  s.module_name = "TwilioVoiceReactNative"
  # <<< FORK

  s.dependency "React-Core"
  s.dependency "TwilioVoice", "6.13.3"
  # >>> FORK KAR-878 — see ForkSentryReporter.h
  s.dependency "Sentry", "9.5.1"
  # <<< FORK
  s.xcconfig  =  { 'VALID_ARCHS' => 'arm64 x86_64' }
  s.pod_target_xcconfig   = { 'VALID_ARCHS[sdk=iphoneos*]' => 'arm64', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'arm64 x86_64' }
end
