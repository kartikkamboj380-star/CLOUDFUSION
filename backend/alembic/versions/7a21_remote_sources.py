"""Add encrypted remote source metadata; preserve all existing tables."""
from alembic import op
import sqlalchemy as sa
revision = "7a21_remote_sources"
down_revision = "5ef3ca6b1d09"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("remote_sources",
        sa.Column("transfer_id", sa.String(36), sa.ForeignKey("transfers.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("url_enc", sa.Text(), nullable=False),
        sa.Column("etag", sa.String(1024), nullable=True),
        sa.Column("source_sha256", sa.String(64), nullable=True))


def downgrade():
    op.drop_table("remote_sources")
