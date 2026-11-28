String registrationOpeningDateForApi(String value) {
  if (!RegExp(r'^[0-9]{8}$').hasMatch(value)) return value;
  return '${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6)}';
}
