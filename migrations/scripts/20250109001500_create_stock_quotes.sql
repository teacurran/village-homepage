-- //  create stock_quotes table
-- Migration SQL that makes the change goes here.

CREATE TABLE stock_quotes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol TEXT NOT NULL,
    company_name TEXT NOT NULL,
    quote_data JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_stock_quotes_symbol ON stock_quotes(symbol);
CREATE INDEX idx_stock_quotes_expires ON stock_quotes(expires_at);

COMMENT ON TABLE stock_quotes IS 'Cache for stock market quotes from Alpha Vantage API';
COMMENT ON COLUMN stock_quotes.symbol IS 'Stock ticker symbol (uppercase, e.g., AAPL)';
COMMENT ON COLUMN stock_quotes.quote_data IS 'Serialized StockQuoteType with price, change, sparkline data';
COMMENT ON COLUMN stock_quotes.fetched_at IS 'When the quote was last fetched from API';
COMMENT ON COLUMN stock_quotes.expires_at IS 'When the cached quote expires (dynamic based on market hours)';

-- //@UNDO
-- SQL to undo the change goes here.

DROP TABLE IF EXISTS stock_quotes;
