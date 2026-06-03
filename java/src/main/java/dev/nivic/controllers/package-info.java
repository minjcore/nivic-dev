/**
 * HTTP <strong>controllers</strong> (request adapters): thin entry points that parse input, call
 * domain services, and map results to status / body. In this WAR, primary wallet traffic is still
 * {@link dev.nivic.sevlet.SevletWalletPayloadServlet}; add types here when splitting servlet logic
 * into smaller, testable handlers without changing the public URL mapping.
 */
package dev.nivic.controllers;
