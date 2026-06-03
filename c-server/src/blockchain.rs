use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{info, warn};
use web3::Web3;
use std::collections::HashSet;
use crate::{event::EventPublisher, Metrics};

pub struct BlockchainListener {
    rpc_url: String,
    custody_wallet: String,
    connected: Arc<RwLock<bool>>,
}

impl BlockchainListener {
    pub async fn new(rpc_url: &str, _usdt: &str, _usdc: &str, wallet: &str) -> Self {
        let connected = Arc::new(RwLock::new(false));

        if let Ok(web3) = Web3::new(web3::transports::Http::new(rpc_url).ok().unwrap()) {
            match web3.eth().block_number().await {
                Ok(_) => {
                    *connected.write().await = true;
                    info!("✓ Connected to blockchain: {}", rpc_url);
                }
                Err(e) => {
                    warn!("Failed to connect to blockchain: {}", e);
                }
            }
        }

        Self {
            rpc_url: rpc_url.to_string(),
            custody_wallet: wallet.to_string(),
            connected,
        }
    }

    pub async fn is_connected(&self) -> bool {
        *self.connected.read().await
    }

    pub async fn start(
        &self,
        _publisher: Arc<EventPublisher>,
        _metrics: Arc<RwLock<Metrics>>,
    ) {
        info!("Starting blockchain listener (stub mode - detection disabled)");
        info!("Watching custody wallet: {}", self.custody_wallet);

        // Placeholder: in production, would poll blockchain for ERC20 transfers
        // to custody_wallet and publish CRYPTO_DEPOSIT_INITIATED events
        tokio::spawn(async {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(30)).await;
            }
        });
    }
}
