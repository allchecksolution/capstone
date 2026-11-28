part of '../allcheck_ui.dart';

const _registrationLengths = {
  'name': 100,
  'phone': 15,
  'email': 254,
  'address': 200,
  'addressDetail': 200,
  'vehicle': 20,
  'businessNumber': 10,
  'openedOn': 8,
  'company': 100,
  'owner': 100,
  'companyPhone': 20,
  'businessType': 100,
  'businessItem': 100,
  'companyAddress': 200,
  'companyAddressDetail': 200,
  'bank': 50,
  'bankAccount': 30,
  'bankOwner': 50,
  'taxEmail': 254,
};

bool _registrationEmail(String key) => key == 'email' || key == 'taxEmail';

const _registrationAddressDetails = {
  'addressDetail': 'address',
  'companyAddressDetail': 'companyAddress',
};

String _joinRegistrationAddress(String base, String detail) =>
    [base.trim(), detail.trim()].where((part) => part.isNotEmpty).join(' ');

Json _registrationProfile(Json fields) {
  final profile = {...fields};
  for (final entry in _registrationAddressDetails.entries) {
    profile[entry.value] = _joinRegistrationAddress(
      text(profile, entry.value),
      profile.remove(entry.key)?.toString() ?? '',
    );
  }
  return profile;
}

const _registrationNumericFields = {
  'phone',
  'companyPhone',
  'bankAccount',
  'businessNumber',
  'openedOn',
};

String _registrationInitialValue(String key, String value) =>
    _registrationNumericFields.contains(key)
    ? value.replaceAll(RegExp(r'[^0-9]'), '')
    : value;

List<TextInputFormatter> _registrationFormatters(String key) {
  final limit = _registrationLengths[key];
  final numeric = _registrationNumericFields.contains(key);
  return [
    if (numeric)
      FilteringTextInputFormatter.digitsOnly,
    if (_registrationEmail(key))
      FilteringTextInputFormatter.allow(
        RegExp(r"[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~@-]"),
      ),
    if (_registrationEmail(key))
      TextInputFormatter.withFunction(
        (previous, next) =>
            '@'.allMatches(next.text).length > 1 ? previous : next,
      ),
    // Validate the full value so a pasted number cannot change silently.
    if (limit != null && !numeric) LengthLimitingTextInputFormatter(limit),
  ];
}

String? _registrationError(String key, String text) {
  final limit = _registrationLengths[key];
  if (limit != null && text.length > limit) return '$limit자 이하로 입력해 주세요.';
  return switch (key) {
    'phone' when !RegExp(r'^0[0-9]{8,14}$').hasMatch(text) =>
      '휴대폰 번호는 (-) 없이 숫자로 입력해 주세요.',
    'companyPhone' when !RegExp(r'^[0-9]{9,20}$').hasMatch(text) =>
      '회사 대표번호는 (-) 없이 숫자 9~20자리로 입력해 주세요.',
    'businessNumber' when !RegExp(r'^[0-9]{10}$').hasMatch(text) =>
      '사업자등록번호는 숫자 10자리로 입력해 주세요.',
    'openedOn' when !validOpeningDate(registrationOpeningDateForApi(text)) =>
      '개업일자는 (-) 없이 실제 날짜를 숫자 8자리로 입력해 주세요.',
    'bankAccount' when !RegExp(r'^[0-9]{6,30}$').hasMatch(text) =>
      '계좌번호는 (-) 없이 숫자 6~30자리로 입력해 주세요.',
    'email' || 'taxEmail'
        when !RegExp(
          r"^[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$",
        ).hasMatch(text) =>
      '이메일 형식을 확인해 주세요.',
    _ => null,
  };
}

const _emailDomains = [
  'naver.com',
  'gmail.com',
  'daum.net',
  'hanmail.net',
  'kakao.com',
  'outlook.com',
];

extension _RegistrationFields on _WorkflowShellState {
  Widget registrationField(
    String key,
    String label,
    String initial,
    String hint,
  ) {
    final email = _registrationEmail(key);
    final baseAddressKey = _registrationAddressDetails[key];
    final address = key == 'address' || key == 'companyAddress';
    final addressSearch =
        address &&
        !kIsWeb &&
        (defaultTargetPlatform == TargetPlatform.android ||
            defaultTargetPlatform == TargetPlatform.iOS);
    final input = field(
      key,
      label,
      initial: _registrationInitialValue(key, initial),
      helperText: hint,
      required: baseAddressKey == null,
      keyboard: email
          ? TextInputType.emailAddress
          : _registrationNumericFields.contains(key)
          ? TextInputType.number
          : null,
      inputFormatters: _registrationFormatters(key),
      extraValidator: (inputValue) {
        if (baseAddressKey != null &&
            _joinRegistrationAddress(value(baseAddressKey), inputValue).length >
                _registrationLengths[baseAddressKey]!) {
          return '기본 주소와 상세주소를 합해 200자 이하로 입력해 주세요.';
        }
        return _registrationError(key, inputValue);
      },
      suffixIcon: addressSearch
          ? IconButton(
              tooltip: '주소 검색',
              icon: const Icon(Icons.search),
              onPressed: busy ? null : () => _searchRegistrationAddress(key),
            )
          : null,
    );
    if (!email) return input;
    final emailController = controller(key, initial);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        input,
        ValueListenableBuilder<TextEditingValue>(
          valueListenable: emailController,
          builder: (context, value, _) {
            final at = value.text.indexOf('@');
            if (at < 1 || !value.composing.isCollapsed)
              return const SizedBox.shrink();
            final prefix = value.text.substring(at + 1).toLowerCase();
            final domains = _emailDomains.where(
              (domain) => domain.startsWith(prefix),
            );
            if (_emailDomains.contains(prefix)) return const SizedBox.shrink();
            return Wrap(
              spacing: 8,
              children: [
                for (final domain in domains)
                  if (at + 1 + domain.length <= _registrationLengths[key]!)
                    ActionChip(
                      label: Text('@$domain'),
                      onPressed: () {
                        final completed =
                            '${value.text.substring(0, at + 1)}$domain';
                        emailController.value = TextEditingValue(
                          text: completed,
                          selection: TextSelection.collapsed(
                            offset: completed.length,
                          ),
                        );
                      },
                    ),
              ],
            );
          },
        ),
      ],
    );
  }

  Future<void> _searchRegistrationAddress(String key) => platformTask(() async {
    final input = controller(key);
    final step = registrationStep;
    FocusManager.instance.primaryFocus?.unfocus();
    try {
      final html = await rootBundle.loadString(
        'packages/allcheck_ui/assets/address_search.html',
      );
      if (!mounted || page != 'register' || registrationStep != step) return;
      final result = await _native.invokeMethod<String>(
        'searchRegistrationAddress',
        {'html': html},
      );
      if (!mounted ||
          page != 'register' ||
          registrationStep != step ||
          result == null)
        return;
      final address = result.trim();
      if (address.isEmpty || _registrationError(key, address) != null) return;
      input.value = TextEditingValue(
        text: address,
        selection: TextSelection.collapsed(offset: address.length),
      );
    } catch (_) {
      if (mounted) message('주소 검색창을 열 수 없습니다. 주소를 직접 입력해 주세요.');
    }
  });
}
