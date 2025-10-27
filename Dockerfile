# Use the latest Python 3.11 slim image
FROM python:3.11-slim

# Set working directory inside the container
WORKDIR /usr/src/app

# Install pip dependencies first (for better cache)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy all other source code to the working directory
COPY . .

# Expose the port FastAPI runs on (default: 8000)
EXPOSE 8080

# Start the FastAPI app using Uvicorn
# Adjust 'main:app' if your entry point or variable is different
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--log-config", "logging.yaml"]

