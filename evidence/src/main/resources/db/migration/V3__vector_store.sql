CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store(
    id UUID PRIMARY KEY ,
    content TEXT NOT NULL ,
    metadata JSONB,
    embedding VECTOR(${vector.dimension})
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
ON vector_store USING HNSW (embedding vector_cosine_ops);
