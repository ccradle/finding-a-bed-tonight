###############################################################################
# Full Environment — Remote State Backend
# Finding A Bed Tonight
###############################################################################

terraform {
  backend "s3" {
    bucket         = "fabt-terraform-state"
    key            = "environments/full/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "fabt-terraform-locks"
    encrypt        = true
  }
}
