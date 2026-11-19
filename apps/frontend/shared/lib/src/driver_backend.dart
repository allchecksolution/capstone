part of 'backend.dart';

/// Maps the driver's onboarding screens to the backend's /api/v1 contracts.
/// External service keys stay on the backend; service tokens stay in memory.
class DriverOnboardingBackend implements FrontendBackend {
  DriverOnboardingBackend(String baseUrl, {http.Client? client})
    : _session = HttpFrontendBackend(baseUrl, client: client, restAuth: true);
  final HttpFrontendBackend _session;
  ApiData? _registration;
  ApiData? _user;
  int _generation = 0;

  @override
  void clearSession() {
    _generation++;
    _registration = null;
    _user = null;
    _session.clearSession();
  }

  void close() {
    clearSession();
    _session.close();
  }

  @override
  Future<ApiData> call(String operation, [ApiData data = const {}]) async {
    final generation = _generation;
    switch (operation) {
      case 'auth/kakao':
        final login = await _session.call(operation, {
          'kakaoAccessToken': data['accessToken'],
        });
        _checkGeneration(generation);
        _user = Map<String, dynamic>.from(login['user'] as Map);
        final state = await _fetchState(generation);
        return {'account': state['account'], 'state': state};
      case 'auth/logout':
        try {
          final token = _session._refreshToken;
          if (token != null) {
            await _session.call(operation, {'refreshToken': token});
          }
          return {};
        } finally {
          clearSession();
        }
      case 'workflow/state':
        return {'state': await _fetchState(generation)};
      case 'drivers/business-check':
        final result = await _session._request(operation, {
          'businessRegistrationNumber': data['businessNumber'],
          'openedOn': data['openedOn'],
          'representativeName': data['owner'],
        }, path: 'drivers/business-verifications');
        _checkGeneration(generation);
        if (result['status'] != 'VERIFIED') {
          throw const ServiceFailure(0, '사업자 확인 응답을 확인해 주세요.');
        }
        return {'verified': true};
      case 'drivers/register':
        final flat = Map<String, dynamic>.from(data['profile'] as Map);
        final registration = _requestRegistration(flat);
        final resubmitting = _registration?['accountStatus'] == 'REJECTED';
        final result = await _session._request(
          operation,
          resubmitting
              ? {
                  'expectedVersion': _registration!['version'],
                  'registration': registration,
                }
              : registration,
          path: resubmitting
              ? 'drivers/me/registration/resubmission'
              : 'drivers/me/registration',
          headers: {'Idempotency-Key': data['requestId'] as String},
        );
        _checkGeneration(generation);
        _registration = {...result, ...registration};
        return {'state': _state()};
      // The backend does not yet expose a notification inbox. Push registration
      // and other workflow commands require their own REST adapters.
      case 'notifications/list':
        return {'items': <ApiData>[]};
      default:
        throw const ServiceFailure(501, '이 기능은 아직 서버에 연결되지 않았습니다.');
    }
  }

  void _checkGeneration(int generation) {
    if (generation != _generation) {
      throw const ServiceFailure(499, '요청이 취소되었습니다.');
    }
  }

  Future<ApiData> _fetchState(int generation) async {
    try {
      final registration = await _session._request(
        'drivers/registration',
        {},
        method: 'GET',
        path: 'drivers/me/registration',
      );
      _checkGeneration(generation);
      _registration = registration;
    } on ServiceFailure catch (error) {
      _checkGeneration(generation);
      if (error.status != 404 || error.code != 'DRIVER_NOT_FOUND') rethrow;
      _registration = null;
    }
    return _state();
  }

  static const _workTypes = {
    '막힘': 'BLOCKAGE',
    '내벽 누수': 'INTERIOR_LEAK',
    '외벽 누수': 'EXTERIOR_LEAK',
  };
  ApiData _requestRegistration(ApiData flat) {
    final specialties = (flat['specialties'] as List).map((value) {
      final workType = _workTypes[value];
      if (workType == null) {
        throw const ServiceFailure(400, '현재 가입 가능한 전문 분야를 선택해 주세요.');
      }
      return workType;
    }).toList();
    List<String> collection(String key, String other) => {
      ...((flat[key] as List?) ?? []).cast<String>(),
      if ((flat[other] as String?)?.trim().isNotEmpty == true)
        (flat[other] as String).trim(),
    }.toList();
    return {
      'profile': {
        'workerName': flat['name'],
        'phone': flat['phone'],
        'email': flat['email'],
        'residence': flat['address'],
        'vehicleNumber': flat['vehicle'],
        'settlementAccount': {
          'bankName': flat['bank'],
          'accountNumber': flat['bankAccount'],
          'holderName': flat['bankOwner'],
        },
        'taxInvoiceEmail': flat['taxEmail'],
        'specialties': specialties,
        'activityRegions': collection('regions', 'otherRegion'),
        'equipment': collection('equipment', 'otherEquipment'),
      },
      'business': {
        'businessName': flat['company'],
        'businessRegistrationNumber': (flat['businessNumber'] as String)
            .replaceAll('-', ''),
        'openedOn': flat['openedOn'],
        'representativeName': flat['owner'],
        'companyPhone': flat['companyPhone'],
        'businessType': flat['businessType'],
        'businessItem': flat['businessItem'],
        'companyAddress': flat['companyAddress'],
      },
    };
  }

  ApiData _state() {
    final registration = _registration;
    ApiData? account;
    if (registration != null) {
      final profile = registration['profile'] as Map;
      final business = registration['business'] as Map;
      final bank = profile['settlementAccount'] as Map;
      final status = switch (registration['accountStatus']) {
        'PENDING_APPROVAL' => '승인 대기',
        'ACTIVATED' => '활성',
        'REJECTED' => '반려',
        'WITHDRAWN' => '탈퇴',
        _ => throw const ServiceFailure(0, '가입 상태 응답을 확인해 주세요.'),
      };
      account = {
        'accountId': _user?['accountId'],
        'driverId': registration['driverId'],
        'status': status,
        'version': registration['version'],
        'reason': (registration['rejection'] as Map?)?['reason'],
        'profile': {
          'name': profile['workerName'],
          'phone': profile['phone'],
          'email': profile['email'],
          'address': profile['residence'],
          'vehicle': profile['vehicleNumber'],
          'bank': bank['bankName'],
          'bankAccount': bank['accountNumber'],
          'bankOwner': bank['holderName'],
          'taxEmail': profile['taxInvoiceEmail'],
          'specialties': (profile['specialties'] as List)
              .map(
                (value) =>
                    _workTypes.entries.firstWhere((e) => e.value == value).key,
              )
              .toList(),
          'regions': profile['activityRegions'],
          'equipment': profile['equipment'],
          'company': business['businessName'],
          'businessNumber': business['businessRegistrationNumber'],
          'openedOn': business['openedOn'],
          'owner': business['representativeName'],
          'companyPhone': business['companyPhone'],
          'businessType': business['businessType'],
          'businessItem': business['businessItem'],
          'companyAddress': business['companyAddress'],
        },
      };
    }
    return {
      'schema': 1,
      'account': account,
      'draft': <String, dynamic>{},
      'jobs': <ApiData>[],
      'drivers': <ApiData>[],
      'consultations': <ApiData>[],
      'reports': <String, dynamic>{},
      'cancellations': <ApiData>[],
      'threads': <String, dynamic>{},
      'messages': <ApiData>[],
      'role': 'editor',
      'availability': '휴식',
      'notifications': [true, true, true, true],
      'location': '미확인',
    };
  }
}
