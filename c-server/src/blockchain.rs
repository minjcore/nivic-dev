use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{info, warn};
use web3::Web3;
use std::collections::HashSet;
use crate::{event::EventPublisher, Metrics};

const TRANSFER_TOPIC: &str = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

pub struct BlockchainListener {
    rpc_url: String,
    usdt_contract: String,
    usdc_contract: String,
    custody_wallet: String,
    connected: Arc<RwLock<bool>>,
}

impl BlockchainListener {
    pub async fn new(rpc_url: &str, usdt: &str, usdc: &str, wallet: &str) -> Self {
        let connected = Arc::new(RwLock::new(false));

        match web3::transports::Http::new(rpc_url) {
            Ok(http) => {
                let web3 = Web3::new(http);
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
            Err(e) => warn!("Failed to create HTTP transport: {}", e),
        }

        Self {
            rpc_url: rpc_url.to_string(),
            usdt_contract: usdt.to_string(),
            usdc_contract: usdc.to_string(),
            custody_wallet: wallet.to_string(),
            connected,
        }
    }

    pub async fn is_connected(&self) -> bool {
        *self.connected.read().await
    }

    pub async fn start(
        &self,
        publisher: Arc<EventPublisher>,
        metrics: Arc<RwLock<Metrics>>,
    ) {
        info!("✓ Starting blockchain listener");
        info!("Watching USDT: {}", self.usdt_contract);
        info!("Watching USDC: {}", self.usdc_contract);
        info!("Custody wallet: {}", self.custody_wallet);

        let rpc_url = self.rpc_url.clone();
        let usdt = self.usdt_contract.clone();
        let usdc = self.usdc_contract.clone();
        let wallet = self.custody_wallet.clone();
        let mut last_block = 0u64;
        let mut seen_txs = HashSet::new();

        tokio::spawn(async move {
            loop {
                if let Ok(http) = web3::transports::Http::new(&rpc_url) {
                    let web3 = Web3::new(http);
                    match web3.eth().block_number().await {
                        Ok(current_block) => {
                            let current: u64 = current_block.as_u64();
                            if current > last_block {
                                let from_block = last_block.max(current.saturating_sub(1000));
                                info!("Checking blocks {} → {}", from_block, current);

                                for contract in &[usdt.as_str(), usdc.as_str()] {
                                    let transfer_topic: web3::types::H256 = TRANSFER_TOPIC.parse().unwrap_or_default();
                                    let wallet_topic: web3::types::H256 = wallet.parse().unwrap_or_default();

                                    if let Ok(logs) = web3
                                        .eth()
                                        .logs(web3::types::FilterBuilder::default()
                                            .address(vec![contract.parse().unwrap_or_default()])
                                            .topics(Some(vec![transfer_topic]), None, Some(vec![wallet_topic]), None)
                                            .from_block(web3::types::BlockNumber::Number(from_block.into()))
                                            .to_block(web3::types::BlockNumber::Number(current.into()))
                                            .build())
                                        .await
                                    {
                                        for log in logs {
                                            let tx_hash = format!("0x{:x}", log.transaction_hash.unwrap_or_default());
                                            if seen_txs.contains(&tx_hash) {
                                                continue;
                                            }
                                            seen_txs.insert(tx_hash.clone());

                                            let amount_hex = if log.data.0.len() >= 32 {
                                                format!("0x{}", hex::encode(&log.data.0[..32]))
                                            } else {
                                                "0x0".to_string()
                                            };

                                            let token = if contract.to_lowercase() == usdt.to_lowercase() {
                                                "USDT"
                                            } else {
                                                "USDC"
                                            };

                                            let deposit_id = format!("{}-{}", tx_hash, log.log_index.unwrap_or_default());

                                            let pub_err = publisher
                                                .publish_deposit_initiated(
                                                    &deposit_id,
                                                    token,
                                                    &amount_hex,
                                                    &tx_hash,
                                                    log.block_number.map(|b: web3::types::U64| b.as_u64()).unwrap_or(current),
                                                )
                                                .await
                                                .err()
                                                .map(|e| format!("{}", e));

                                            let mut m = metrics.write().await;
                                            if let Some(err_msg) = pub_err {
                                                warn!("Failed to publish event: {}", err_msg);
                                                m.errors += 1;
                                            } else {
                                                m.deposits_detected += 1;
                                                m.events_published += 1;
                                            }
                                        }
                                    }
                                }

                                last_block = current;
                            }
                        }
                        Err(e) => warn!("Failed to get block number: {}", e),
                    }
                } else {
                    warn!("Failed to create HTTP transport");
                }

                tokio::time::sleep(tokio::time::Duration::from_secs(15)).await;
            }
        });
    }
}
