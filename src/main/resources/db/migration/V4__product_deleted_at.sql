-- "Delete" for a product that already has batches: it leaves every list, the batches and the
-- statistics stay. A product without batches is deleted outright instead.
ALTER TABLE products ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
