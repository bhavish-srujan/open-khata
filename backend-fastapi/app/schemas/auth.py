from typing import Optional
from pydantic import BaseModel, Field

from app.schemas.base import CamelModel


class RequestOtpDto(CamelModel):
    phone_number: str = Field(
        ...,
        pattern=r"^[+0-9][0-9\s\-()]{5,19}$",
        description="Phone number to send OTP to",
    )


class VerifyOtpDto(CamelModel):
    phone_number: str = Field(
        ...,
        pattern=r"^[+0-9][0-9\s\-()]{5,19}$",
        description="Phone number",
    )
    otp: str = Field(
        ...,
        pattern=r"^[0-9]{6}$",
        description="Six digit OTP",
    )


class RequestOtpResponse(CamelModel):
    success: bool
    message: str
    otp: Optional[str] = None


class LoginResponse(BaseModel):
    """
    Android client reads `access_token` and `is_new_user` in snake_case.
    Preserved as snake_case.
    """
    access_token: str
    is_new_user: bool


class LogoutResponse(CamelModel):
    success: bool
