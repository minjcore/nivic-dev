use tracing::info;

pub struct WalletManager;

impl WalletManager {
    pub fn new() -> Self {
        info!("WalletManager initialized");
        WalletManager
    }
}
