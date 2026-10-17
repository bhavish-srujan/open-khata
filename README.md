Open Khata 📒

Simple, Smart & Digital Bookkeeping for Small Businesses

Open Khata is a digital bookkeeping application designed to help small businesses replace traditional paper-based khata books with a convenient digital ledger.

Manage customers, record transactions, track outstanding balances, and keep financial records organized in one place. With features such as digital ledger management, transaction tracking, payment reminders, and Scan Khata, Open Khata simplifies everyday bookkeeping.

The goal is simple: make bookkeeping easier, faster, and more accessible for every small business.

✨ Features

📒 Digital Ledger Management

Create and manage customer records.

Maintain individual customer ledgers.

Record money given and money received.

Track outstanding balances.

View transaction history for individual customers.

📸 Scan Khata

Digitize traditional paper-based ledger records.

Upload images of existing khata records.

Extract relevant ledger information from images.

Convert extracted information into structured data.

Review extracted records before proceeding.

Reduce the effort required to enter existing ledger information manually.

🔐 Authentication & Security

OTP-based authentication.

JWT-based authorization.

Protected API endpoints.

Server-side request validation.

Secure management of application credentials.

Business-level data isolation.

💰 Transaction Tracking

Record GAVE and GOT transactions.

Maintain customer transaction histories.

Track outstanding payments.

Keep financial records organized.

📲 Payment Reminders

Send payment reminders to customers.

Help businesses follow up on outstanding balances.

Integrate messaging services for customer communication.

🔄 Offline-Friendly Synchronization

Queue eligible operations when connectivity is unavailable.

Synchronize queued operations when connectivity returns.

Support a more reliable bookkeeping experience in environments with inconsistent internet access.

🏪 Multi-Business Support

Organize records by business.

Keep business data appropriately separated.

Scope operations to the relevant business context.

🏗️ System Architecture

Open Khata follows a client-server architecture. The Android application communicates with a FastAPI backend through REST APIs. The backend handles business logic, authentication, database operations, and external service integrations.

flowchart TD
    A["Android App<br/>Kotlin + Jetpack Compose"]
    B["FastAPI Backend<br/>Python"]
    C[("PostgreSQL Database")]
    D["External Services<br/>Image Processing / SMS / Messaging"]

    A <-->|"HTTPS / REST API"| B
    B <--> C
    B <--> D

Note: This diagram describes the intended architecture. Confirm external integrations and implementation details against the current codebase before publication.

🛠️ Tech Stack

Component

Technology

Android Development

Kotlin

UI Framework

Jetpack Compose

Backend Framework

FastAPI

Programming Language

Python

API Architecture

REST

Database

PostgreSQL

Authentication

OTP and JWT

Image Processing

Google Gemini API

SMS Integration

TextBee

Messaging Integration

WhatsApp API

Backend Deployment

Render

Note: The ORM/database access library is not specified here because it should be verified against the current FastAPI codebase.

📸 How Scan Khata Works

Scan Khata helps users transfer information from traditional paper-based ledgers into a digital format.

Select Khata Image
        │
        ▼
Upload Image
        │
        ▼
Backend Processing
        │
        ▼
Extract Ledger Information
        │
        ▼
Structure the Extracted Data
        │
        ▼
Review the Results
        │
        ▼
Confirm the Information

Workflow

Select an image of an existing khata record.

Submit the image for processing.

Extract relevant information from the image.

Convert the extracted information into structured data.

Review the results in the application.

Confirm the information before using it in the bookkeeping workflow.

This feature helps reduce manual data entry when transitioning from physical ledgers to digital bookkeeping.

🔐 Security

Open Khata follows a backend-centered approach to application security.

Authentication: OTP-based authentication and JWT-based authorization.

API protection: Secure endpoints that require authenticated access.

Data isolation: Restrict access to the appropriate business records.

Credential management: Keep sensitive API credentials on the backend.

Input validation: Validate requests before processing them.

Financial accuracy: Validate transaction data and ledger calculations independently of extracted image content.

Secure communication: Use HTTPS for production API requests.

📂 Project Structure

The repository contains the Android application and the FastAPI backend.

open-khata/
├── android/                 # Android application
├── backend-fastapi/         # FastAPI backend
└── README.md                # Project documentation

The internal file structure may vary depending on the current implementation.

⚙️ Getting Started

Prerequisites

Python 3.10+ (use the version required by the backend project)

pip

PostgreSQL

Android Studio

A compatible JDK

Required external service credentials

1. Clone the Repository

git clone <YOUR_REPOSITORY_URL>
cd open-khata

2. Set Up the FastAPI Backend

cd backend-fastapi

Create and activate a virtual environment if you use one:

python -m venv .venv

On Windows PowerShell:

.\.venv\Scripts\Activate.ps1

Install dependencies using the dependency file present in the repository. If the project contains requirements.txt, run:

pip install -r requirements.txt

Configure the environment variables required by the actual backend implementation. Do not commit API keys, database passwords, authentication secrets, or production credentials.

3. Run the Backend

For a typical FastAPI application, run Uvicorn using the actual Python module and FastAPI application variable name. For example, if the entry point is main.py and the app variable is app:

uvicorn main:app --reload

If your entry point differs, replace main:app with the correct import path. Verify that the server starts and connects to PostgreSQL.

4. Run the Android Application

Open the android/ project in Android Studio.

Configure the backend API base URL.

Sync the Gradle project.

Build the application.

Run it on an emulator or physical Android device.

Ensure that the configured API URL is reachable from the device.

🌐 Core Modules

Module

Responsibility

Authentication

User authentication and access control

User Management

Manage user information

Business Management

Organize business records

Customer Management

Create and manage customer records

Ledger Management

Maintain customer ledgers

Transaction Management

Record GAVE/GOT transactions

Scan Khata

Extract information from ledger images

Payment Reminders

Support customer payment follow-ups

Refer to the actual backend controllers and API documentation for implemented routes and request formats.

🚀 Deployment

The backend can be deployed using Render, with PostgreSQL serving as the application's relational database.

Typical deployment steps include:

Provision a PostgreSQL database.

Configure the production database connection.

Set the required environment variables.

Configure external service credentials.

Deploy the backend.

Verify database connectivity and API availability.

Configure the Android application to use the production API.

Test authentication, ledger operations, and transaction handling.

🧪 Testing

Important areas to validate include:

OTP authentication and JWT authorization.

Customer creation and management.

Transaction creation and updates.

Ledger balance calculations.

Business-level data isolation.

Scan Khata image processing.

Validation of extracted records.

Offline operation queuing and synchronization.

Payment reminder delivery.

Database connectivity and error handling.

Financial records should be validated carefully to maintain accurate customer balances.

💡 Engineering Highlights

Open Khata brings together practical software engineering concepts:

Full-stack application development.

REST API design and implementation.

Modular backend architecture with FastAPI.

Relational database design using PostgreSQL.

Database access through the backend's configured database layer.

Authentication and authorization.

Third-party service integrations.

Image-processing workflows.

Offline operation queuing and synchronization.

Business-level data isolation.

🔮 Future Enhancements

Potential improvements include:

Advanced ledger search and filtering.

Financial reports and summaries.

Exportable transaction statements.

Improved offline synchronization.

More comprehensive transaction audit trails.

Enhanced image-processing accuracy.

Automated testing for financial workflows.

Better error handling and application monitoring.

These are potential improvements, not claims about currently implemented functionality.

🎯 Project Objective

Open Khata aims to simplify bookkeeping for small businesses by replacing manual record-keeping with a structured digital ledger.

By combining customer management, transaction tracking, balance monitoring, and convenient ledger digitization, the application helps businesses maintain organized financial records with less manual effort.

Open Khata — Your business ledger, simplified.
