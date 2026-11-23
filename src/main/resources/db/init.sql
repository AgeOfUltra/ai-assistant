-- Initialize pgvector extensions required by Spring AI PgVectorStore
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create the vector_store table used by Spring AI for RAG document embeddings
-- Dimensions: 3072 (matching gemini-embedding-001 model)
CREATE TABLE IF NOT EXISTS public.vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(3072)
);

-- Note: HNSW index is NOT created here because pgvector limits HNSW to 2000 dimensions,
-- but Gemini embeddings use 3072 dimensions. The application is configured with
-- index-type: NONE, so similarity search will use a brute-force scan instead.
