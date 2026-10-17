import os
from abc import ABC, abstractmethod
from pathlib import Path
from typing import NamedTuple, Optional

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from app.config import settings

# Content sniffing signatures
SIGNATURES = [
    ("image/jpeg", b"\xff\xd8\xff"),
    ("image/png", b"\x89PNG\r\n\x1a\n"),
    ("application/pdf", b"%PDF"),
]


def detect_mime(buffer: bytes) -> Optional[str]:
    """Sniffs real content type from leading magic bytes."""
    if not buffer:
        return None
    for mime, magic in SIGNATURES:
        if buffer.startswith(magic):
            return mime
    return None


def extension_for(mime: str) -> str:
    """Safe extension based on verified MIME type, never taken from user input."""
    if mime == "image/jpeg":
        return ".jpg"
    elif mime == "image/png":
        return ".png"
    elif mime == "application/pdf":
        return ".pdf"
    return ".bin"


class StoredObject(NamedTuple):
    key: str
    size: int
    content_type: str


class StorageService(ABC):
    @abstractmethod
    async def put(self, key: str, body: bytes, content_type: str) -> StoredObject:
        pass

    @abstractmethod
    async def get(self, key: str) -> bytes:
        pass

    @abstractmethod
    async def get_signed_url(self, key: str, ttl_seconds: int = 120) -> Optional[str]:
        pass

    @abstractmethod
    async def exists(self, key: str) -> bool:
        pass

    @abstractmethod
    async def delete(self, key: str) -> None:
        pass


class LocalStorageService(StorageService):
    def __init__(self, root_dir: Optional[str] = None):
        self.root = Path(root_dir or settings.UPLOADS_DIR).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def _path_for(self, key: str) -> Path:
        full_path = (self.root / key).resolve()
        # Prevent path traversal attacks
        if full_path != self.root and not str(full_path).startswith(str(self.root) + os.sep):
            raise ValueError("Invalid storage key")
        return full_path

    async def put(self, key: str, body: bytes, content_type: str) -> StoredObject:
        path = self._path_for(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
        return StoredObject(key=key, size=len(body), content_type=content_type)

    async def get_signed_url(self, key: str, ttl_seconds: int = 120) -> Optional[str]:
        # Local disk does not support signed URLs; caller streams directly
        return None

    async def get(self, key: str) -> bytes:
        path = self._path_for(key)
        if not path.is_file():
            raise FileNotFoundError("Stored object not found")
        return path.read_bytes()

    async def exists(self, key: str) -> bool:
        try:
            return self._path_for(key).is_file()
        except Exception:
            return False

    async def delete(self, key: str) -> None:
        try:
            path = self._path_for(key)
            if path.is_file():
                path.unlink()
        except Exception:
            pass


class S3StorageService(StorageService):
    def __init__(self):
        s3_config = Config(
            signature_version="s3v4",
            s3={"addressing_style": "path" if settings.S3_FORCE_PATH_STYLE else "auto"},
        )
        kwargs = {
            "aws_access_key_id": settings.S3_ACCESS_KEY_ID,
            "aws_secret_access_key": settings.S3_SECRET_ACCESS_KEY,
            "region_name": settings.S3_REGION,
            "config": s3_config,
        }
        if settings.S3_ENDPOINT:
            kwargs["endpoint_url"] = settings.S3_ENDPOINT

        self.s3_client = boto3.client("s3", **kwargs)
        self.bucket = settings.S3_BUCKET

    async def put(self, key: str, body: bytes, content_type: str) -> StoredObject:
        extra_args = {"ContentType": content_type}
        if settings.S3_SERVER_SIDE_ENCRYPTION:
            extra_args["ServerSideEncryption"] = settings.S3_SERVER_SIDE_ENCRYPTION

        self.s3_client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=body,
            **extra_args,
        )
        return StoredObject(key=key, size=len(body), content_type=content_type)

    async def get_signed_url(self, key: str, ttl_seconds: int = 120) -> Optional[str]:
        return self.s3_client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": key},
            ExpiresIn=ttl_seconds,
        )

    async def get(self, key: str) -> bytes:
        resp = self.s3_client.get_object(Bucket=self.bucket, Key=key)
        return resp["Body"].read()

    async def exists(self, key: str) -> bool:
        try:
            self.s3_client.head_object(Bucket=self.bucket, Key=key)
            return True
        except ClientError as e:
            if e.response["Error"]["Code"] == "404":
                return False
            raise

    async def delete(self, key: str) -> None:
        self.s3_client.delete_object(Bucket=self.bucket, Key=key)


def get_storage_service() -> StorageService:
    s3_vars = [
        settings.S3_BUCKET,
        settings.S3_ACCESS_KEY_ID,
        settings.S3_SECRET_ACCESS_KEY,
    ]
    configured = [v for v in s3_vars if v and v.strip()]

    if len(configured) == len(s3_vars):
        return S3StorageService()

    if len(configured) > 0:
        raise ValueError("Object storage is partially configured.")

    if settings.NODE_ENV == "production":
        raise ValueError("Object storage is required in production.")

    return LocalStorageService()
