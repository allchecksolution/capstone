import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart' as kakao;

/// Kakao credentials are kept in memory and must be verified by the service
/// backend before it issues a service session or decides driver approval.
class KakaoIdentity {
  const KakaoIdentity({required this.userId, required this.accessToken,
    this.nickname, this.email});
  final String userId;
  final String accessToken;
  final String? nickname;
  final String? email;
}

abstract interface class DriverAuth {
  Future<KakaoIdentity?> signIn();
  Future<void> signOut();
}

class KakaoDriverAuth implements DriverAuth {
  static const nativeAppKey = String.fromEnvironment('KAKAO_NATIVE_APP_KEY');
  static void initialize() {
    if (nativeAppKey.isNotEmpty && !kIsWeb) {
      kakao.KakaoSdk.init(nativeAppKey: nativeAppKey);
    }
  }

  bool _cancelled(Object e) =>
      e is PlatformException && e.code == 'CANCELED' ||
      e is kakao.KakaoClientException &&
          e.reason == kakao.ClientErrorCause.cancelled;

  @override
  Future<KakaoIdentity?> signIn() async {
    if (kIsWeb || ![TargetPlatform.android, TargetPlatform.iOS].contains(defaultTargetPlatform)) {
      throw StateError('카카오 로그인은 Android 또는 iOS 앱에서 이용해 주세요.');
    }
    if (nativeAppKey.isEmpty) {
      throw StateError('로그인을 사용할 수 없습니다. 관리자에게 문의해 주세요.');
    }
    try {
      kakao.OAuthToken token;
      if (await kakao.isKakaoTalkInstalled()) {
        try {
          token = await kakao.UserApi.instance.loginWithKakaoTalk();
        } catch (e) {
          if (_cancelled(e)) return null;
          token = await kakao.UserApi.instance.loginWithKakaoAccount();
        }
      } else {
        token = await kakao.UserApi.instance.loginWithKakaoAccount();
      }
      final user = await kakao.UserApi.instance.me();
      return KakaoIdentity(userId: user.id.toString(), accessToken: token.accessToken,
        nickname: user.kakaoAccount?.profile?.nickname, email: user.kakaoAccount?.email);
    } catch (e) {
      if (_cancelled(e)) return null;
      // Never display SDK errors: they may contain credentials or user data.
      throw StateError('카카오 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    }
  }

  @override
  Future<void> signOut() async {
    if (nativeAppKey.isEmpty || kIsWeb) return;
    try { await kakao.UserApi.instance.logout(); }
    catch (_) { await kakao.TokenManagerProvider.instance.manager.clear(); }
  }
}
