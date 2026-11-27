import Flutter
import UIKit
import WebKit
import firebase_messaging

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  private var addressSearchChannel: FlutterMethodChannel?
  private weak var addressSearch: RegistrationAddressSearchController?

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    FLTFirebaseMessagingPlugin.configureNotificationCenterDelegate()
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let channel = FlutterMethodChannel(
      name: "allcheck/platform", binaryMessenger: engineBridge.applicationRegistrar.messenger()
    )
    addressSearchChannel = channel
    channel.setMethodCallHandler { [weak self] call, result in
      guard call.method == "searchRegistrationAddress" else {
        result(FlutterMethodNotImplemented)
        return
      }
      guard let self, self.addressSearch == nil,
        let arguments = call.arguments as? [String: Any],
        let html = arguments["html"] as? String,
        let window = UIApplication.shared.connectedScenes
          .compactMap({ $0 as? UIWindowScene })
          .flatMap({ $0.windows }).first(where: { $0.isKeyWindow }),
        let root = window.rootViewController
      else {
        result(FlutterError(code: "ADDRESS_SEARCH", message: "주소 검색창을 열 수 없습니다.", details: nil))
        return
      }
      var presenter = root
      while let next = presenter.presentedViewController { presenter = next }
      let search = RegistrationAddressSearchController(html: html, completion: result)
      self.addressSearch = search
      let navigation = UINavigationController(rootViewController: search)
      navigation.modalPresentationStyle = .fullScreen
      presenter.present(navigation, animated: true)
    }
  }
}

private final class RegistrationAddressSearchController: UIViewController, WKNavigationDelegate {
  private let html: String
  private var completion: FlutterResult?
  private let baseURL = URL(string: "https://appassets.androidplatform.net/")!

  init(html: String, completion: @escaping FlutterResult) {
    self.html = html
    self.completion = completion
    super.init(nibName: nil, bundle: nil)
  }

  required init?(coder: NSCoder) { return nil }

  override func viewDidLoad() {
    super.viewDidLoad()
    title = "주소 검색"
    navigationItem.leftBarButtonItem = UIBarButtonItem(
      title: "닫기", style: .plain, target: self, action: #selector(closeSearch)
    )
    let browser = WKWebView()
    browser.navigationDelegate = self
    browser.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(browser)
    NSLayoutConstraint.activate([
      browser.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
      browser.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
      browser.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      browser.trailingAnchor.constraint(equalTo: view.trailingAnchor),
    ])
    browser.loadHTMLString(html, baseURL: baseURL)
  }

  @objc private func closeSearch() { finish(nil) }

  private func finish(_ address: String?) {
    guard let completion else { return }
    self.completion = nil
    dismiss(animated: true) { completion(address) }
  }

  func webView(
    _ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
    decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
  ) {
    guard let url = action.request.url, let frame = action.targetFrame else {
      decisionHandler(.cancel)
      return
    }
    if !frame.isMainFrame {
      decisionHandler(url.scheme == "https" || url.absoluteString == "about:blank" ? .allow : .cancel)
      return
    }
    if url == baseURL || url.absoluteString == "about:blank" {
      decisionHandler(.allow)
      return
    }
    decisionHandler(.cancel)
    guard url.scheme == "allcheck-address", url.host == "selection",
      let address = URLComponents(url: url, resolvingAgainstBaseURL: false)?
        .queryItems?.first(where: { $0.name == "address" })?.value,
      !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
      address.utf16.count <= 200
    else { return }
    finish(address)
  }
}
