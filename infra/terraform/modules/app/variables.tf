###############################################################################
# Application Module — Variables
# Finding A Bed Tonight
###############################################################################

variable "tier" {
  description = "Deployment tier: lite, standard, or full"
  type        = string
  validation {
    condition     = contains(["lite", "standard", "full"], var.tier)
    error_message = "tier must be one of: lite, standard, full"
  }
}

variable "aws_region" {
  description = "AWS region for the deployment"
  type        = string
  default     = "us-east-1"
}

variable "vpc_id" {
  description = "VPC ID where the application will be deployed"
  type        = string
}

variable "public_subnet_ids" {
  description = "List of public subnet IDs for the ALB"
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for ECS tasks"
  type        = list(string)
}

# --- Container images ---

variable "backend_image" {
  description = "Docker image for the backend container"
  type        = string
  default     = "ghcr.io/ccradle/finding-a-bed-tonight-backend:latest"
}

variable "frontend_image" {
  description = "Docker image for the frontend container"
  type        = string
  default     = "ghcr.io/ccradle/finding-a-bed-tonight-frontend:latest"
}

# --- Database connection ---

variable "db_jdbc_url" {
  description = "JDBC URL for the database (app role)"
  type        = string
}

variable "db_username" {
  description = "Database username (app role)"
  type        = string
  sensitive   = true
}

variable "db_password" {
  description = "Database password (app role)"
  type        = string
  sensitive   = true
}

# --- Optional: Redis (Standard/Full) ---

variable "redis_host" {
  description = "Redis endpoint host (empty string if not used)"
  type        = string
  default     = ""
}

variable "redis_port" {
  description = "Redis endpoint port"
  type        = number
  default     = 6379
}

# --- Optional: Kafka (Full only) ---

variable "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers (empty string if not used)"
  type        = string
  default     = ""
}

# --- Application settings ---

variable "spring_profiles_active" {
  description = "Spring Boot active profiles"
  type        = string
  default     = "prod"
}

variable "cors_origins" {
  description = "Allowed CORS origins"
  type        = string
  default     = "*"
}

variable "jwt_secret" {
  description = "JWT signing secret"
  type        = string
  sensitive   = true
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS listener (empty string disables HTTPS)"
  type        = string
  default     = ""
}

variable "tags" {
  description = "Additional tags to apply to all resources"
  type        = map(string)
  default     = {}
}
