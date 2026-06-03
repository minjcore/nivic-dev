use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{info, warn};
use web3::Web3;
use web3::contract::Contract;
use web3::types::{Address, U256, H256};
use std::collections::HashSet;
use crate::{event::EventPublisher, Metrics};

pub struct BlockchainListener {
    rpc_url: String,
    usdt_contract: String,
    usdc_contract: String,
    custody_wallet: String,
    processed_txs: Arc<RwLock<HashSet<String>>>,
    last_block: Arc<RwLock<u64>>,
    connected: Arc<RwLock<bool>>,
}

impl BlockchainListener {
    pub async fn new(rpc_url: &str, usdt: &str, usdc: &str, wallet: &str) -> Self {
        let connected = Arc::new(RwLock::new(false));

        // Try to connect
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
            usdt_contract: usdt.to_string(),
            usdc_contract: usdc.to_string(),
            custody_wallet: wallet.to_string(),
            processed_txs: Arc::new(RwLock::new(HashSet::new())),
            last_block: Arc::new(RwLock::new(0)),
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
        info!("Starting blockchain listener");

        let rpc_url = self.rpc_url.clone();
        let usdt = self.usdt_contract.clone();
        let usdc = self.usdc_contract.clone();
        let wallet = self.custody_wallet.clone();
        let processed = self.processed_txs.clone();
        let last_block = self.last_block.clone();

        tokio::spawn(async move {
            loop {
                if let Ok(web3) = Web3::new(
                    web3::transports::Http::new(&rpc_url)
                        .ok()
                        .unwrap()
                ) {
                    match web3.eth().block_number().await {
                        Ok(current) => {
                            let current_num = current.as_u64();
                            let mut last = last_block.write().await;

                            if *last == 0 {
                                *last = current_num.saturating_sub(100);
                                info!("Starting scan from block {}", last);
                                drop(last);
                                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                                continue;
                            }

                            if current_num > *last {
                                info!("Scanning blocks {} → {}", last, current_num);
                                for block_num in (*last + 1)..=current_num {
                                    let _ = scan_block(
                                        web3.clone(),
                                        block_num,
                                        &usdt,
                                        &usdc,
                                        &wallet,
                                        processed.clone(),
                                        publisher.clone(),
                                        metrics.clone(),
                                    )
                                    .await;
                                }
                                *last = current_num;
                            }
                        }
                        Err(e) => {
                            warn!("Error getting block number: {}", e);
                        }
                    }
                }
                tokio::time::sleep(tokio::time::Duration::from_secs(12)).await;
            }
        });
    }
}

async fn scan_block(
    web3: Web3<web3::transports::Http>,
    block_num: u64,
    usdt: &str,
    usdc: &str,
    wallet: &str,
    processed: Arc<tokio::sync::RwLock<HashSet<String>>>,
    publisher: Arc<EventPublisher>,
    metrics: Arc<tokio::sync::RwLock<Metrics>>,
) -> Result<(), Box<dyn std::error::Error>> {
    let block = web3.eth().block_with_txs(block_num.into()).await?;

    if let Some(block) = block {
        let custody_lower = wallet.to_lowercase();

        for tx in block.transactions {
            if let Some(to) = tx.to {
                let to_lower = to.to_string().to_lowercase();

                // Check if it's a token transfer to our wallet
                if (to_lower.contains(&usdt.to_lowercase()) ||
                    to_lower.contains(&usdc.to_lowercase())) &&
                    tx.input.len() >= 68 {

                    let input = &tx.input.to_vec();
                    // Check ERC20 Transfer signature
                    if hex::encode(&input[0..4]) == "a9059cbb" {
                        // Decode recipient (bytes 4-36)
                        if let Ok(recipient) = hex::encode(&input[4..36]) {
                            if recipient.ends_with(&custody_lower) {
                                // Decode amount (bytes 36-68)
                                let amount = hex::encode(&input[36..68]);

                                let tx_hash = format!("{:x}", tx.hash);
                                let processed_lock = processed.read().await;

                                if !processed_lock.contains(&tx_hash) {
                                    drop(processed_lock);

                                    let token_type = if to_lower.contains(&usdt.to_lowercase()) {
                                        "USDT"
                                    } else {
                                        "USDC"
                                    };

                                    info!("Deposit detected: {} {} from {}", amount, token_type, tx.from);

                                    // Publish event
                                    let _ = publisher.publish_deposit_initiated(
                                        &format!("dep-{}", &tx_hash[..8]),
                                        token_type,
                                        &amount,
                                        &tx_hash,
                                        block_num,
                                    ).await;

                                    metrics.write().await.deposits_detected += 1;
                                    processed.write().await.insert(tx_hash);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Ok(())
}
